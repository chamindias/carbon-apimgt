/*
*  Copyright (c) WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
*  WSO2 Inc. licenses this file to you under the Apache License,
*  Version 2.0 (the "License"); you may not use this file except
*  in compliance with the License.
*  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*/

package org.wso2.carbon.apimgt.impl;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Plan;
import com.stripe.model.Product;
import com.stripe.net.RequestOptions;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.Monetization;
import org.wso2.carbon.apimgt.api.model.Tier;
import org.wso2.carbon.apimgt.impl.dao.ApiMgtDAO;
import org.wso2.carbon.apimgt.impl.internal.ServiceReferenceHolder;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.apimgt.impl.utils.TierNameComparator;
import org.wso2.carbon.registry.core.Registry;
import org.wso2.carbon.registry.core.Resource;
import org.wso2.carbon.registry.core.exceptions.RegistryException;
import org.wso2.carbon.user.api.UserStoreException;

import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Default implementation for API monetization
 */
public class MonetizationImpl implements Monetization {

    private static final Log log = LogFactory.getLog(MonetizationImpl.class);
    ApiMgtDAO apiMgtDAO = ApiMgtDAO.getInstance();

    @Override
    public boolean configureMonetization(boolean isMonetizationEnabled, String tenantDomain, API api,
                                         Map<String, String> monetizationProperties) throws APIManagementException {

        String apiName = api.getId().getApiName();
        String apiVersion = api.getId().getVersion();
        String apiProvider = api.getId().getProviderName();
        //get the stripe product ID if it exists in the database
        String existingStripeProductId = getStripeProductIdIfExists(apiName, apiVersion, apiProvider);
        Set<Tier> commercialTiers = new TreeSet<Tier>(new TierNameComparator());

        for (Tier candidateTier : api.getAvailableTiers()) {
            if (APIConstants.COMMERCIAL_TIER_PLAN.equalsIgnoreCase(candidateTier.getTierPlan())) {
                commercialTiers.add(candidateTier);
            }
        }


        //todo
        //remove detached tiers from stripe and DB (if any)
        /*
        get previous tier names for the API from DB
        get attached tier names for the API
        get the difference and identify the removed plans
        delete plans
        remove DB entries
        */


        if (commercialTiers.isEmpty()) {
            String errorMessage = "No paid tiers attached to API : " + api.getId().getApiName() +
                    " , hence skipping monetization.";
            APIUtil.handleException(errorMessage);
        }
        try {
            String stripePlatformAccountKey = getStripePlatformAccountKey(tenantDomain);
            if (StringUtils.isBlank(stripePlatformAccountKey)) {
                String errorMessage = "Stripe platform account key was not found for tenant : " + tenantDomain;
                APIUtil.handleException(errorMessage);
            }
            //get api publisher's stripe key (i.e - connected account stripe key) from monetization properties in request payload
            if (MapUtils.isNotEmpty(monetizationProperties) &&
                    monetizationProperties.containsKey(APIConstants.CONNECTED_ACCOUNT_STRIPE_KEY)) {

                String providerConnectedAccountKey = monetizationProperties.get
                        (APIConstants.CONNECTED_ACCOUNT_STRIPE_KEY);
                if (StringUtils.isBlank(providerConnectedAccountKey)) {
                    String errorMessage = "Connected account stripe key was not found for API : " + apiName;
                    APIUtil.handleException(errorMessage);
                }
                //at this time, we have the stripe key of the platform account and the stripe key of the publisher.
                if (isMonetizationEnabled) {
                    enableMonetization(apiName, apiVersion, apiProvider, commercialTiers, stripePlatformAccountKey,
                            providerConnectedAccountKey, existingStripeProductId);
                } else {
                    disableMonetization(apiName, apiVersion, apiProvider, stripePlatformAccountKey,
                            providerConnectedAccountKey);
                }
                return true;
            } else {
                String errorMessage = "Stripe key of the connected account is empty.";
                APIUtil.handleException(errorMessage);
            }
        } catch (StripeException e) {
            String errorMessage = "Failed to configure stripe : " + e.getMessage();
            APIUtil.handleException(errorMessage, e);
        }
        return false;
    }


    /**
     * This method is used to enable monetization and create necessary artifacts in stripe
     *
     * @param apiName                     API name
     * @param apiVersion                  API version
     * @param apiProvider                 API provider
     * @param commercialTiers             set of commercial tiers in the API
     * @param stripePlatformAccountKey    stripe platform account key
     * @param providerConnectedAccountKey stripe connected account key
     * @param existingStripeProductId     existing stripe product ID
     * @throws APIManagementException if failed to configure monetization
     * @throws StripeException        if failed to create products / plans in stripe
     */
    private void enableMonetization(String apiName, String apiVersion, String apiProvider, Set<Tier> commercialTiers,
                                    String stripePlatformAccountKey, String providerConnectedAccountKey,
                                    String existingStripeProductId) throws APIManagementException, StripeException {

        //check if the amount is mentioned in all paid tiers
        for (Tier currentTier : commercialTiers) {
            if (!currentTier.getTierAttributes().containsKey("amount")) {
                String errorMessage = "Amount is missing in tier : " + currentTier.getName() +
                        " , hence this tier will not be monetized.";
                log.error(errorMessage);
            }
        }
        //get the platform account key and create the product in stripe
        Stripe.apiKey = stripePlatformAccountKey;
        Map<String, Object> platformAccountProductParams = new HashMap<String, Object>();
        String stripeProductName = apiName + "-" + apiVersion + "-" + apiProvider;
        platformAccountProductParams.put("name", stripeProductName);
        platformAccountProductParams.put("type", "service");
        RequestOptions productRequestOptions = RequestOptions.builder().setStripeAccount
                (providerConnectedAccountKey).build();

        Product stripeProduct;
        String stripeProductId;
        Map<String, String> tierPlanMap = new HashMap<>();

        //create a new product in stripe associated with the API (to add plans)
        if (StringUtils.isBlank(existingStripeProductId)) {
            stripeProduct = Product.create(platformAccountProductParams, productRequestOptions);
            stripeProductId = stripeProduct.getId();
            if (StringUtils.isBlank(stripeProductId)) {
                String errorMessage = "Failed to create product in stripe for API : " + apiName;
                APIUtil.handleException(errorMessage);
            }
        } else {
            //get the existing product in stripe associated with the API (to check and add plans)
            stripeProduct = Product.retrieve(existingStripeProductId, productRequestOptions);
            stripeProductId = stripeProduct.getId();
            if (StringUtils.isBlank(stripeProductId)) {
                String errorMessage = "Failed to get product in stripe associated with API : " + apiName;
                APIUtil.handleException(errorMessage);
            }
        }
        //check if stripe plan exists for the current tier in the product
        String apiId = apiMgtDAO.getApiId(apiName, apiVersion, apiProvider);
        Map<String, String> existingTierStripePlanMap = apiMgtDAO.getStripePlanMap(apiId, stripeProductId);
        //create plans for each commercial tier
        for (Tier currentTier : commercialTiers) {
            //if there is a plan associated with the current tier, skip it
            if (existingTierStripePlanMap.containsKey(currentTier.getName())) {
                String errorMessage = "Stripe plan already exists for tier : " + currentTier.getName();
                log.info(errorMessage);
                continue;
            }
            Stripe.apiKey = stripePlatformAccountKey;
            RequestOptions planRequestOptions = RequestOptions.builder().setStripeAccount
                    (providerConnectedAccountKey).build();
            Map<String, Object> planParams = new HashMap<String, Object>();
            int amount = Integer.parseInt(currentTier.getTierAttributes().get("amount").toString());
            planParams.put("amount", amount);
            planParams.put("billing_scheme", "per_unit");
            planParams.put("interval", "month");
            planParams.put("nickname", currentTier.getName());
            planParams.put("product", stripeProduct.getId());
            planParams.put("currency", "usd");
            //create a new stripe plan for the tier
            Plan plan = Plan.create(planParams, planRequestOptions);
            String createdPlanId = plan.getId();
            //put the newly created stripe plans and tiers into a map (to add data to the database)
            if (StringUtils.isNotBlank(createdPlanId)) {
                tierPlanMap.put(currentTier.getName(), createdPlanId);
            } else {
                String errorMessage = "Failed to create stripe plan for tier : " + currentTier.getName();
                APIUtil.handleException(errorMessage);
            }
        }
        //save data in the database - only if there is a stripe product and newly created plans
        if (StringUtils.isNotBlank(stripeProductId) && MapUtils.isNotEmpty(tierPlanMap)) {
            apiMgtDAO.addMonetizationData(apiId, stripeProductId, tierPlanMap);
        }
    }

    /**
     * This method is used to disable monetization and delete relevant artifacts in stripe and DB
     *
     * @param apiName                     API name
     * @param apiVersion                  API version
     * @param apiProvider                 API provider
     * @param stripePlatformAccountKey    stripe platform account key
     * @param providerConnectedAccountKey stripe connected account key
     * @throws StripeException        if failed to delete products / plans in stripe
     * @throws APIManagementException if failed to configure monetization
     */
    private void disableMonetization(String apiName, String apiVersion, String apiProvider,
                                     String stripePlatformAccountKey, String providerConnectedAccountKey)
            throws StripeException, APIManagementException {

        //this is the monetization disable flow
        String apiId = apiMgtDAO.getApiId(apiName, apiVersion, apiProvider);
        List<String> stripePlans = apiMgtDAO.getStripePlanIdListOfApi(apiId);
        //we need to delete the plans, before deleting the product
        if (!stripePlans.isEmpty()) {
            //delete stripe plans for each tier
            for (String planId : stripePlans) {
                Stripe.apiKey = stripePlatformAccountKey;
                RequestOptions planRequestOptions = RequestOptions.builder().
                        setStripeAccount(providerConnectedAccountKey).build();
                Plan stripePlan = Plan.retrieve(planId, planRequestOptions);
                stripePlan.delete(planRequestOptions);
            }
        }
        //delete stripe product associated with the API
        String stripeProductId = apiMgtDAO.getStripeProductId(apiId);
        if (!StringUtils.isEmpty(stripeProductId)) {
            Stripe.apiKey = stripePlatformAccountKey;
            RequestOptions productRequestOptions = RequestOptions.builder().
                    setStripeAccount(providerConnectedAccountKey).build();
            Product stripeApiProduct = Product.retrieve(stripeProductId, productRequestOptions);
            stripeApiProduct.delete(productRequestOptions);
        }
        //clean the respective databse records
        apiMgtDAO.deleteMonetizationData(apiId);
    }


    /**
     * This method is used to get stripe product ID if it exists in the DB
     *
     * @param apiName     API name
     * @param apiVersion  API version
     * @param apiProvider API provider
     * @return stripe product ID if it exists, empty string otherwise
     * @throws APIManagementException if failed to get stripe product ID if it exists in the DB
     */
    private String getStripeProductIdIfExists(String apiName, String apiVersion, String apiProvider)
            throws APIManagementException {

        String apiIdInDatabase = apiMgtDAO.getApiId(apiName, apiVersion, apiProvider);
        String stripeProductId = apiMgtDAO.getStripeProductId(apiIdInDatabase);
        if (StringUtils.isNotBlank(stripeProductId)) {
            //debug
            log.info("Stripe product exists for API : " + apiName + ". Stripe product ID : " + stripeProductId);
            return stripeProductId;
        }
        log.info("Stripe product does not exists for API : " + apiName);
        return StringUtils.EMPTY;
    }

    /**
     * This method is used to get stripe platform account key for a given tenant
     *
     * @param tenantDomain tenant domain
     * @return stripe platform account key for the given tenant
     * @throws APIManagementException if it fails to get stripe platform account key for the given tenant
     */
    private String getStripePlatformAccountKey(String tenantDomain) throws APIManagementException {

        try {
            int tenantId = ServiceReferenceHolder.getInstance().getRealmService().getTenantManager().
                    getTenantId(tenantDomain);
            Registry configRegistry = ServiceReferenceHolder.getInstance().getRegistryService().
                    getConfigSystemRegistry(tenantId);

            if (configRegistry.resourceExists(APIConstants.API_TENANT_CONF_LOCATION)) {
                Resource resource = configRegistry.get(APIConstants.API_TENANT_CONF_LOCATION);
                String tenantConfContent = new String((byte[]) resource.getContent(), Charset.defaultCharset());
                if (StringUtils.isBlank(tenantConfContent)) {
                    String errorMessage = "Tenant configuration for tenant " + tenantDomain +
                            " cannot be empty when configuring monetization.";
                    APIUtil.handleException(errorMessage);
                }

                //get the stripe key of platform account from  tenant conf json file
                JSONObject tenantConfig = (JSONObject) new JSONParser().parse(tenantConfContent);
                JSONObject monetizationInfo = (JSONObject) tenantConfig.get(APIConstants.MONETIZATION_INFO);
                String stripePlatformAccountKey = monetizationInfo.get
                        (APIConstants.PLATFORM_ACCOUNT_STRIPE_KEY).toString();

                if (StringUtils.isBlank(stripePlatformAccountKey)) {
                    String errorMessage = "Stripe platform account key is empty for tenant : " + tenantDomain;
                    APIUtil.handleException(errorMessage);
                }
                return stripePlatformAccountKey;
            }
        } catch (ParseException e) {
            String errorMessage = "Error while parsing tenant configuration in tenant : " + tenantDomain;
            APIUtil.handleException(errorMessage, e);
        } catch (UserStoreException e) {
            String errorMessage = "Failed to get the corresponding tenant configurations for tenant :  " + tenantDomain;
            APIUtil.handleException(errorMessage, e);
        } catch (RegistryException e) {
            String errorMessage = "Failed to get the configuration registry for tenant :  " + tenantDomain;
            APIUtil.handleException(errorMessage, e);
        }
        return StringUtils.EMPTY;
    }
}
