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

import com.google.gson.Gson;
import com.stripe.model.Invoice;
import com.stripe.model.Subscription;
import org.wso2.carbon.apimgt.api.APIProvider;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.APIIdentifier;
import org.wso2.carbon.apimgt.api.model.Monetization;
import org.wso2.carbon.apimgt.api.model.SubscribedAPI;
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
import org.wso2.carbon.registry.core.Registry;
import org.wso2.carbon.registry.core.Resource;
import org.wso2.carbon.registry.core.exceptions.RegistryException;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.utils.multitenancy.MultitenantUtils;

import java.nio.charset.Charset;
import java.sql.Timestamp;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class MonetizationImpl implements Monetization {

    private static final Log log = LogFactory.getLog(MonetizationImpl.class);
    ApiMgtDAO apiMgtDAO = ApiMgtDAO.getInstance();

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
                int amount = Integer.parseInt(subPolicy.getMonetizationPlanProperties().
                        get(APIConstants.PRICE_PER_REQUEST));
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
    public Map<String, String> getMonetizedPoliciesToPlanMapping(API api) throws APIManagementException {

        String apiName = api.getId().getApiName();
        int apiId  = apiMgtDAO.getAPIID(api.getId(), null);
        //get billing engine product ID for that API
        String billingProductIdForApi = getBillingProductIdForApi(apiId);
        if (StringUtils.isEmpty(billingProductIdForApi)) {
            String errorMessage = "Failed to billing engine product ID for  : " + apiName;
            APIUtil.handleException(errorMessage);
        }
        //get tier to billing engine plan mapping
        Map<String, String> tierToBillingEnginePlanMap = apiMgtDAO.getTierToBillingEnginePlanMapping
                (apiId, billingProductIdForApi);
        return tierToBillingEnginePlanMap;
    }

    @Override
    public Map<String, String> getCurrentUsage(String subscriptionUUID, APIProvider apiProvider)
            throws APIManagementException {

        SubscribedAPI subscribedAPI = apiMgtDAO.getSubscriptionByUUID(subscriptionUUID);
        Map<String, String> billingEngineUsageData = new HashMap<String, String>();
        APIIdentifier apiIdentifier = subscribedAPI.getApiId();
        API api = apiProvider.getAPI(apiIdentifier);
        String apiName = apiIdentifier.getApiName();

        if (api.getMonetizationProperties() == null) {
            String errorMessage = "Monetization properties are empty for API : " + apiName;
            APIUtil.handleException(errorMessage);
        }
        HashMap monetizationDataMap = new Gson().fromJson(api.getMonetizationProperties().toString(), HashMap.class);
        if (MapUtils.isEmpty(monetizationDataMap)) {
            String errorMessage = "Monetization data map is empty for API : " + apiName;
            APIUtil.handleException(errorMessage);
        }
        String tenantDomain = MultitenantUtils.getTenantDomain(apiIdentifier.getProviderName());
        //get billing engine platform account key
        String platformAccountKey = getStripePlatformAccountKey(tenantDomain);
        try {
            if (monetizationDataMap.containsKey(APIConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY)) {
                String connectedAccountKey = monetizationDataMap.get
                        (APIConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY).toString();
                if (StringUtils.isBlank(connectedAccountKey)) {
                    String errorMessage = "Connected account stripe key was not found for API : " + apiName;
                    APIUtil.handleException(errorMessage);
                }
                Stripe.apiKey = platformAccountKey;
                //create request options to link with the connected account
                RequestOptions requestOptions = RequestOptions.builder().setStripeAccount(connectedAccountKey).build();
                int apiId = apiMgtDAO.getAPIID(apiIdentifier, null);
                int applicationId = subscribedAPI.getApplication().getId();
                String billingPlanSubscriptionId = apiMgtDAO.getBillingEngineSubscriptionId(apiId, applicationId);
                Subscription billingEngineSubscription = Subscription.retrieve(billingPlanSubscriptionId, requestOptions);
                if (billingEngineSubscription == null) {
                    String errorMessage = "No billing engine subscription was found for API : " + apiName;
                    APIUtil.handleException(errorMessage);
                }
                //upcoming invoice is only applicable for metered usage (i.e - dynamic usage)
                if (!APIConstants.METERED_USAGE.equalsIgnoreCase(billingEngineSubscription.getPlan().getUsageType())) {
                    String errorMessage = "Usage type should be set to 'metered' to get the pending bill.";
                    APIUtil.handleException(errorMessage);
                }
                Map<String, Object> invoiceParams = new HashMap<String, Object>();
                invoiceParams.put("subscription", billingEngineSubscription.getId());
                //fetch the upcoming invoice
                Invoice invoice = Invoice.upcoming(invoiceParams, requestOptions);
                if (invoice == null) {
                    String errorMessage = "No billing engine subscription was found for : " + apiName;
                    APIUtil.handleException(errorMessage);
                }
                //the below parameters are billing engine specific
                billingEngineUsageData.put("invoice_id", invoice.getId());
                billingEngineUsageData.put("object", "invoice");
                billingEngineUsageData.put("account_country", invoice.getAccountCountry());
                billingEngineUsageData.put("account_name", invoice.getAccountName());
                billingEngineUsageData.put("amount_due", invoice.getAmountDue().toString());
                billingEngineUsageData.put("amount_paid", invoice.getAmountPaid().toString());
                billingEngineUsageData.put("amount_remaining", invoice.getAmountRemaining().toString());
                billingEngineUsageData.put("application_fee_amount", invoice.getApplicationFeeAmount().toString());
                billingEngineUsageData.put("attempt_count", invoice.getAttemptCount().toString());
                billingEngineUsageData.put("attempted", invoice.getAttempted().toString());
                billingEngineUsageData.put("billing", invoice.getBilling());
                billingEngineUsageData.put("billing_reason", invoice.getBillingReason());
                billingEngineUsageData.put("charge", invoice.getCharge());
                billingEngineUsageData.put("created", invoice.getCreated().toString());
                billingEngineUsageData.put("currency", invoice.getCurrency());
                billingEngineUsageData.put("customer", invoice.getCustomer());
                billingEngineUsageData.put("customer_address", invoice.getCustomerAddress().toString());
                billingEngineUsageData.put("customer_email", invoice.getCustomerEmail());
                billingEngineUsageData.put("customer_name", invoice.getCustomerName());
                billingEngineUsageData.put("description", invoice.getDescription());
                billingEngineUsageData.put("due_date", invoice.getDueDate().toString());
                billingEngineUsageData.put("ending_balance", invoice.getEndingBalance().toString());
                billingEngineUsageData.put("livemode", invoice.getLivemode().toString());
                billingEngineUsageData.put("next_payment_attempt", invoice.getNextPaymentAttempt().toString());
                billingEngineUsageData.put("number", invoice.getNumber());
                billingEngineUsageData.put("paid", invoice.getPaid().toString());
                billingEngineUsageData.put("payment_intent", invoice.getPaymentIntent());
                billingEngineUsageData.put("period_end", invoice.getPeriodEnd().toString());
                billingEngineUsageData.put("period_start", invoice.getPeriodStart().toString());
                billingEngineUsageData.put("post_payment_credit_notes_amount",
                        invoice.getPostPaymentCreditNotesAmount().toString());
                billingEngineUsageData.put("pre_payment_credit_notes_amount",
                        invoice.getPrePaymentCreditNotesAmount().toString());
                billingEngineUsageData.put("receipt_number", invoice.getReceiptNumber());
                billingEngineUsageData.put("subscription", invoice.getSubscription());
                billingEngineUsageData.put("subtotal", invoice.getSubtotal().toString());
                billingEngineUsageData.put("tax", invoice.getTax().toString());
                billingEngineUsageData.put("tax_percent", invoice.getTaxPercent().toString());
                billingEngineUsageData.put("total", invoice.getTotal().toString());
                billingEngineUsageData.put("total_tax_amounts", invoice.getTotalTaxAmounts().toString());
            }
        } catch (StripeException e) {
            String errorMessage = "Error while fetching billing engine usage data for : " + apiName;
            APIUtil.handleException(errorMessage, e);
        }
        return billingEngineUsageData;
    }

    @Override
    public boolean enableMonetization(String tenantDomain, API api, Map<String, String> monetizationProperties)
            throws APIManagementException {

        //read tenant conf and get platform account key
        String platformAccountKey = getStripePlatformAccountKey(tenantDomain);
        String connectedAccountKey = StringUtils.EMPTY;

        //get api publisher's stripe key (i.e - connected account key) from monetization properties in request payload
        if (MapUtils.isNotEmpty(monetizationProperties) &&
                monetizationProperties.containsKey(APIConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY)) {
            connectedAccountKey = monetizationProperties.get
                    (APIConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY);
            if (StringUtils.isBlank(connectedAccountKey)) {
                String errorMessage = "Connected account stripe key was not found for API : " + api.getId().getApiName();
                APIUtil.handleException(errorMessage);
            }
        } else {
            String errorMessage = "Stripe key of the connected account is empty.";
            APIUtil.handleException(errorMessage);
        }
        String apiName = api.getId().getApiName();
        String apiVersion = api.getId().getVersion();
        String apiProvider = api.getId().getProviderName();
        int apiId = apiMgtDAO.getAPIID(api.getId(), null);
        String billingProductIdForApi = getBillingProductIdForApi(apiId);
        //create billing engine product if it does not exist
        if (StringUtils.isEmpty(billingProductIdForApi)) {
            Stripe.apiKey = platformAccountKey;
            Map<String, Object> productParams = new HashMap<String, Object>();
            String stripeProductName = apiName + "-" + apiVersion + "-" + apiProvider;
            productParams.put(APIConstants.POLICY_NAME_ELEM, stripeProductName);
            productParams.put(APIConstants.TYPE, APIConstants.SERVICE_TYPE);
            RequestOptions productRequestOptions = RequestOptions.builder().setStripeAccount(connectedAccountKey).build();
            try {
                Product product = Product.create(productParams, productRequestOptions);
                billingProductIdForApi = product.getId();
            } catch (StripeException e) {
                APIUtil.handleException("Unable to create product in billing engine for : " + apiName, e);
            }
        }
        Map<String, String> tierPlanMap = new HashMap<>();
        //scan for commercial tiers and add add plans in the billing engine if needed
        for (Tier currentTier : api.getAvailableTiers()) {
            if (APIConstants.COMMERCIAL_TIER_PLAN.equalsIgnoreCase(currentTier.getTierPlan())) {
                String billingPlanId = getBillingPlanIdOfTier(apiId, currentTier.getName());
                if (StringUtils.isBlank(billingPlanId)) {
                    int tenantId = APIUtil.getTenantId(apiProvider);
                    String createdPlanId = createBillingPlanForCommercialTier(currentTier, tenantId, platformAccountKey,
                            connectedAccountKey, billingProductIdForApi);
                    if (StringUtils.isNotBlank(createdPlanId)) {
                        log.debug("Billing plan : " + createdPlanId + " successfully created for : " +
                                currentTier.getName());
                        tierPlanMap.put(currentTier.getName(), createdPlanId);
                    } else {
                        log.debug("Failed to create billing plan for : " + currentTier.getName());
                    }
                }
            }
        }
        //save data in the database - only if there is a stripe product and newly created plans
        if (StringUtils.isNotBlank(billingProductIdForApi) && MapUtils.isNotEmpty(tierPlanMap)) {
            apiMgtDAO.addMonetizationData(apiId, billingProductIdForApi, tierPlanMap);
        }
        return true;
    }

    /**
     * Get billing product ID for a given API
     *
     * @param apiId API ID
     * @return  billing product ID for the given API
     * @throws APIManagementException if failed to get billing product ID for the given API
     */
    private String getBillingProductIdForApi(int apiId) throws APIManagementException {

        String billingProductId = StringUtils.EMPTY;
        billingProductId = apiMgtDAO.getBillingEngineProductId(apiId);
        return billingProductId;
    }

    /**
     * Get billing plan ID for a given tier
     *
     * @param apiId API ID
     * @param tierName tier name
     * @return billing plan ID for a given tier
     * @throws APIManagementException if failed to get billing plan ID for the given tier
     */
    private String getBillingPlanIdOfTier(int apiId, String tierName) throws APIManagementException {

        String billingPlanId = StringUtils.EMPTY;
        billingPlanId = apiMgtDAO.getBillingEnginePlanIdForTier(apiId, tierName);
        return billingPlanId;
    }

    /**
     * Create billing plan for a given commercial tier
     *
     * @param tier tier
     * @param tenantId tenant ID
     * @param platformAccountKey billing engine platform account key
     * @param connectedAccountKey billing engine connected account key
     * @param billingProductId billing engine product ID
     * @return created plan ID in billing engine
     * @throws APIManagementException if fails to create billing plan
     */
    private String createBillingPlanForCommercialTier(Tier tier, int tenantId, String platformAccountKey,
                                                      String connectedAccountKey, String billingProductId)
            throws APIManagementException {

        String tierUUID = apiMgtDAO.getSubscriptionPolicy(tier.getName(), tenantId).getUUID();
        //get plan ID from mapping table
        String planId = apiMgtDAO.getBillingPlanId(tierUUID);
        Stripe.apiKey = platformAccountKey;
        try {
            //get that plan details
            Plan billingPlan = Plan.retrieve(planId);
            //get the values from that plan and replicate it
            Map<String, Object> planParams = new HashMap<String, Object>();
            planParams.put(APIConstants.AMOUNT, billingPlan.getAmount());
            planParams.put(APIConstants.BILLING_SCHEME, billingPlan.getBillingScheme());
            planParams.put(APIConstants.INTERVAL, billingPlan.getInterval());
            planParams.put(APIConstants.PRODUCT_NICKNAME, billingPlan.getNickname());
            planParams.put(APIConstants.PRODUCT, billingProductId);
            planParams.put(APIConstants.CURRENCY, billingPlan.getCurrency());
            planParams.put(APIConstants.USAGE_TYPE, billingPlan.getUsageType());
            RequestOptions planRequestOptions = RequestOptions.builder().setStripeAccount(connectedAccountKey).build();
            //create a new stripe plan for the tier
            Plan createdPlan = Plan.create(planParams, planRequestOptions);
            return createdPlan.getId();
        } catch (StripeException e) {
            APIUtil.handleException("Unable to create billing plan for : " + tier.getName(), e);
        }
        return StringUtils.EMPTY;
    }

    @Override
    public boolean disableMonetization(String tenantDomain, API api, Map<String, String> monetizationProperties)
            throws APIManagementException {

        //read tenant conf and get platform account key
        String platformAccountKey = getStripePlatformAccountKey(tenantDomain);
        String connectedAccountKey = StringUtils.EMPTY;

        //get api publisher's stripe key (i.e - connected account key) from monetization properties in request payload
        if (MapUtils.isNotEmpty(monetizationProperties) &&
                monetizationProperties.containsKey(APIConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY)) {
            connectedAccountKey = monetizationProperties.get
                    (APIConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY);
            if (StringUtils.isBlank(connectedAccountKey)) {
                String errorMessage = "Billing engine connected account key was not found for API : " +
                        api.getId().getApiName();
                APIUtil.handleException(errorMessage);
            }
        } else {
            String errorMessage = "Stripe key of the connected account is empty.";
            APIUtil.handleException(errorMessage);
        }
        String apiName = api.getId().getApiName();
        String apiVersion = api.getId().getVersion();
        String apiProvider = api.getId().getProviderName();

        //String apiId = apiMgtDAO.getApiId(apiName, apiVersion, apiProvider);

        int apiId = apiMgtDAO.getAPIID(api.getId(), null);

        String billingProductIdForApi = getBillingProductIdForApi(apiId);
        Map<String, String> tierToBillingEnginePlanMap = apiMgtDAO.getTierToBillingEnginePlanMapping
                (apiId, billingProductIdForApi);
        Stripe.apiKey = platformAccountKey;
        RequestOptions requestOptions = RequestOptions.builder().setStripeAccount(connectedAccountKey).build();
        try {
            for (Map.Entry<String, String> entry : tierToBillingEnginePlanMap.entrySet()) {
                String planId = entry.getValue();
                Plan plan = Plan.retrieve(planId, requestOptions);
                plan.delete(requestOptions);
                log.debug("Successfully deleted billing plan : " + planId + " of tier : " + entry.getKey());
            }
            //after deleting all the associated plans, then delete the product
            Product product = Product.retrieve(billingProductIdForApi, requestOptions);
            product.delete(requestOptions);
            log.debug("Successfully deleted billing product : " + billingProductIdForApi + " of API : " + apiName);
            //after deleting plans and the product, clean the database records
            apiMgtDAO.deleteMonetizationData(apiId);
            log.debug("Successfully deleted monetization database records for API : " + apiName);
        } catch (StripeException e) {
            String errorMessage = "Failed to delete products and plans in the billing engine.";
            APIUtil.handleException(errorMessage, e);
        }
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
