/*
*  Copyright (c), WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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

package org.wso2.carbon.apimgt.impl.monetization;

import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.Monetization;
import org.wso2.carbon.apimgt.api.model.Tier;
import org.wso2.carbon.apimgt.api.model.policy.SubscriptionPolicy;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.internal.ServiceReferenceHolder;
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
import org.wso2.carbon.apimgt.impl.dao.ApiMgtDAO;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.apimgt.impl.utils.TierNameComparator;
import org.wso2.carbon.registry.core.Registry;
import org.wso2.carbon.registry.core.Resource;
import org.wso2.carbon.registry.core.exceptions.RegistryException;
import org.wso2.carbon.user.api.UserStoreException;
import java.nio.charset.Charset;
import java.sql.Timestamp;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class MonetizationImpl implements Monetization {

    private static final Log log = LogFactory.getLog(MonetizationImpl.class);

    @Override
    public boolean createBillingPlan(SubscriptionPolicy subPolicy)
            throws APIManagementException {

        //read tenant conf and get platform account key
        Stripe.apiKey = getStripePlatformAccountKey(subPolicy.getTenantDomain());
        Map<String, Object> productParams = new HashMap<String, Object>();
        productParams.put(APIConstants.POLICY_NAME_ELEM, subPolicy.getTenantDomain() + "-" + subPolicy.getPolicyName());
        productParams.put(APIConstants.TYPE, APIConstants.SERVICE_TYPE);
        Timestamp timestamp = new Timestamp(new Date().getTime());
        String productCreationIdempotencyKey = subPolicy.getTenantDomain() + timestamp.toString();
        RequestOptions productRequestOptions = RequestOptions.builder().
                setIdempotencyKey(productCreationIdempotencyKey).build();
        try {
            Product product = Product.create(productParams, productRequestOptions);
            String productId = product.getId();
            if (StringUtils.isBlank(productId)) {
                String errorMessage = "Failed to create stripe product for tenant : " + subPolicy.getTenantDomain();
                APIUtil.handleException(errorMessage);
            }
            Map<String, Object> planParams = new HashMap<String, Object>();
            planParams.put(APIConstants.CURRENCY, APIConstants.USD);
            planParams.put(APIConstants.PRODUCT, productId);
            planParams.put(APIConstants.PRODUCT_NICKNAME, subPolicy.getPolicyName());
            planParams.put(APIConstants.INTERVAL,
                    subPolicy.getMonetizationPlanProperties().get(APIConstants.BILLING_CYCLE));

            if (APIConstants.FIXED_RATE.equalsIgnoreCase(subPolicy.getMonetizationPlan())) {
                int amount = Integer.parseInt(subPolicy.getMonetizationPlanProperties().get(APIConstants.FIXED_PRICE));
                planParams.put(APIConstants.AMOUNT, amount);
                planParams.put(APIConstants.USAGE_TYPE, APIConstants.LICENSED_USAGE);
            }
            if (APIConstants.DYNAMIC_RATE.equalsIgnoreCase(subPolicy.getMonetizationPlan())) {
                int amount = Integer.parseInt(subPolicy.getMonetizationPlanProperties().
                        get(APIConstants.PRICE_PER_REQUEST));
                planParams.put(APIConstants.AMOUNT, amount);
                planParams.put(APIConstants.USAGE_TYPE, APIConstants.METERED_USAGE);
            }
            RequestOptions planRequestOptions = RequestOptions.builder().setIdempotencyKey(subPolicy.getUUID()).build();
            Plan plan = Plan.create(planParams, planRequestOptions);
            String createdPlanId = plan.getId();
            //put the newly created stripe plans and tiers into a map (to add data to the database)
            if (StringUtils.isBlank(createdPlanId)) {
                String errorMessage = "Failed to create plan for tier : " + subPolicy.getPolicyName() +
                        " in " + subPolicy.getTenantDomain();
                APIUtil.handleException(errorMessage);
            }
            ApiMgtDAO apiMgtDAO = ApiMgtDAO.getInstance();
            //apiMgtDAO.addSubscriptionPolicyMonetizationData(subPolicy, monetizationPlan, monetizationPlanProperties);
            apiMgtDAO.addMonetizationPlanData(subPolicy, productId, createdPlanId);
            return true;
        } catch (StripeException e) {
            String errorMessage = "Failed to create monetization plan for : " + subPolicy.getPolicyName();
            APIUtil.handleException(errorMessage);
        }
        return false;
    }

    @Override
    public boolean updateBillingPlan(SubscriptionPolicy subPolicy) throws APIManagementException {

        ApiMgtDAO apiMgtDAO = ApiMgtDAO.getInstance();
        Map<String, String> planData = apiMgtDAO.getPlanData(subPolicy);
        String oldProductId = null, oldPlanId = null, newProductId = null, updatedPlanId = null;
        //read tenant-conf.json and get platform account key
        Stripe.apiKey = getStripePlatformAccountKey(subPolicy.getTenantDomain());
        if (MapUtils.isNotEmpty(planData)) {
            //product and plan exists for the older plan, so get those values and proceed
            oldProductId = planData.get(APIConstants.PRODUCT_ID);
            oldPlanId = planData.get(APIConstants.PLAN_ID);
        } else {
            //this means updating the monetization plan of tier from a free to commercial.
            //since there is no plan (for old - free tier), we should create a product and plan for the updated tier
            Map<String, Object> productParams = new HashMap<String, Object>();
            productParams.put(APIConstants.POLICY_NAME_ELEM,
                    subPolicy.getTenantDomain() + "-" + subPolicy.getPolicyName());
            productParams.put(APIConstants.TYPE, APIConstants.SERVICE_TYPE);
            Timestamp timestamp = new Timestamp(new Date().getTime());
            String productCreationIdempotencyKey = subPolicy.getTenantDomain() + timestamp.toString();
            RequestOptions productRequestOptions = RequestOptions.builder().
                    setIdempotencyKey(productCreationIdempotencyKey).build();
            try {
                Product product = Product.create(productParams, productRequestOptions);
                newProductId = product.getId();
                if (StringUtils.isBlank(newProductId)) {
                    String errorMessage = "No stripe product was created for tenant (when updating policy) : " +
                            subPolicy.getTenantDomain();
                    APIUtil.handleException(errorMessage);
                }
            } catch (StripeException e) {
                String errorMessage = "Failed to create stripe product for tenant (when updating policy) : " +
                        subPolicy.getTenantDomain();
                APIUtil.handleException(errorMessage);
            }
        }
        //delete old plan if exists
        if (StringUtils.isNotBlank(oldPlanId)) {
            try {
                Plan.retrieve(oldPlanId).delete();
            } catch (StripeException e) {
                String errorMessage = "Failed to delete old plan for tier.";
                APIUtil.handleException(errorMessage);
            }
        }
        //if updated to a commercial plan, create new plan in billing engine and update DB record
        if (APIConstants.COMMERCIAL_TIER_PLAN.equalsIgnoreCase(subPolicy.getBillingPlan())) {
            Map<String, Object> planParams = new HashMap<String, Object>();
            planParams.put(APIConstants.CURRENCY, APIConstants.USD);
            if (StringUtils.isNotBlank(oldProductId)) {
                planParams.put(APIConstants.PRODUCT, oldProductId);

            }
            if (StringUtils.isNotBlank(newProductId)) {
                planParams.put(APIConstants.PRODUCT, newProductId);

            }
            planParams.put(APIConstants.PRODUCT_NICKNAME, subPolicy.getPolicyName());
            planParams.put(APIConstants.INTERVAL, subPolicy.getMonetizationPlanProperties().
                    get(APIConstants.BILLING_CYCLE));

            if (APIConstants.FIXED_RATE.equalsIgnoreCase(subPolicy.getMonetizationPlan())) {
                int amount = Integer.parseInt(subPolicy.getMonetizationPlanProperties().get(APIConstants.FIXED_PRICE));
                planParams.put(APIConstants.AMOUNT, amount);
                planParams.put(APIConstants.USAGE_TYPE, APIConstants.LICENSED_USAGE);
            }
            if (APIConstants.DYNAMIC_RATE.equalsIgnoreCase(subPolicy.getMonetizationPlan())) {
                int amount = Integer.parseInt(subPolicy.getMonetizationPlanProperties().get(APIConstants.PRICE_PER_REQUEST));
                planParams.put(APIConstants.AMOUNT, amount);
                planParams.put(APIConstants.USAGE_TYPE, APIConstants.METERED_USAGE);
            }
            Plan updatedPlan = null;
            try {
                updatedPlan = Plan.create(planParams);
            } catch (StripeException e) {
                String errorMessage = "Failed to create stripe plan for tier : " + subPolicy.getPolicyName();
                APIUtil.handleException(errorMessage);
            }
            if (updatedPlan != null) {
                updatedPlanId = updatedPlan.getId();
            } else {
                String errorMessage = "Failed to create plan for policy update : " + subPolicy.getPolicyName();
                APIUtil.handleException(errorMessage);
            }
            if (StringUtils.isBlank(updatedPlanId)) {
                String errorMessage = "Failed to update stripe plan for tier : " + subPolicy.getPolicyName() +
                        " in " + subPolicy.getTenantDomain();
                APIUtil.handleException(errorMessage);
            }

        } else if (APIConstants.BILLING_PLAN_FREE.equalsIgnoreCase(subPolicy.getBillingPlan())) {
            //If updated to a free plan (from a commercial plan), no need to create any plan in the billing engine
            //Hence delete DB record
            apiMgtDAO.deleteMonetizationPlanData(subPolicy);
            //Remove old artifacts in the billing engine (if any)
            try {
                if (StringUtils.isNotBlank(oldProductId)) {
                    Product.retrieve(oldProductId).delete();
                }
            } catch (StripeException e) {
                String errorMessage = "Failed to delete old stripe product for : " + subPolicy.getPolicyName();
                APIUtil.handleException(errorMessage);
            }
        }
        if (StringUtils.isNotBlank(oldProductId)) {
            //update DB record
            apiMgtDAO.updateMonetizationPlanData(subPolicy, oldProductId, updatedPlanId);
        }
        if (StringUtils.isNotBlank(newProductId)) {
            //create new DB record
            apiMgtDAO.addMonetizationPlanData(subPolicy, newProductId, updatedPlanId);
        }
        return true;
    }

    @Override
    public boolean deleteBillingPlan(SubscriptionPolicy subPolicy) throws APIManagementException {

        ApiMgtDAO apiMgtDAO = ApiMgtDAO.getInstance();
        //get old plan (if any) in the billing engine and delete
        Map<String, String> planData = apiMgtDAO.getPlanData(subPolicy);
        if (MapUtils.isEmpty(planData)) {
            log.debug("No billing plan found for : " + subPolicy.getPolicyName());
            return true;
        }
        String productId = planData.get(APIConstants.PRODUCT_ID);
        String planId = planData.get(APIConstants.PLAN_ID);
        //read tenant-conf.json and get platform account key
        Stripe.apiKey = getStripePlatformAccountKey(subPolicy.getTenantDomain());
        if (StringUtils.isNotBlank(planId)) {
            try {
                Plan.retrieve(planId).delete();
                Product.retrieve(productId).delete();
                apiMgtDAO.deleteMonetizationPlanData(subPolicy);
            } catch (StripeException e) {
                String errorMessage = "Failed to delete billing plan resources of : " + subPolicy.getPolicyName();
                APIUtil.handleException(errorMessage);
            }
        }
        return true;
    }

    @Override
    public boolean enableMonetization(String tenantDomain, API api, Map<String, String> monetizationProperties)
            throws APIManagementException {





        return false;


















    }

    @Override
    public boolean disableMonetization(String tenantDomain, API api, Map<String, String> monetizationProperties)
            throws APIManagementException {

        return true;
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
                    throw new APIManagementException(errorMessage);
                }
                //get the stripe key of platform account from  tenant conf json file
                JSONObject tenantConfig = (JSONObject) new JSONParser().parse(tenantConfContent);
                JSONObject monetizationInfo = (JSONObject) tenantConfig.get(APIConstants.MONETIZATION_INFO);
                String stripePlatformAccountKey = monetizationInfo.get
                        (APIConstants.BILLING_ENGINE_PLATFORM_ACCOUNT_KEY).toString();
                if (StringUtils.isBlank(stripePlatformAccountKey)) {
                    String errorMessage = "Stripe platform account key is empty for tenant : " + tenantDomain;
                    throw new APIManagementException(errorMessage);
                }
                return stripePlatformAccountKey;
            }
        } catch (ParseException e) {
            String errorMessage = "Error while parsing tenant configuration in tenant : " + tenantDomain;
            throw new APIManagementException(errorMessage);
        } catch (UserStoreException e) {
            String errorMessage = "Failed to get the corresponding tenant configurations for tenant :  " + tenantDomain;
            throw new APIManagementException(errorMessage);
        } catch (RegistryException e) {
            String errorMessage = "Failed to get the configuration registry for tenant :  " + tenantDomain;
            throw new APIManagementException(errorMessage);
        }
        return StringUtils.EMPTY;
    }
}
