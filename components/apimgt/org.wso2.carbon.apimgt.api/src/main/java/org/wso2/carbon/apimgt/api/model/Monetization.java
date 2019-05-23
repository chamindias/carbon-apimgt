package org.wso2.carbon.apimgt.api.model;


import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.policy.SubscriptionPolicy;

import java.util.Map;

public interface Monetization {

    boolean createBillingPlan(SubscriptionPolicy subPolicy) throws APIManagementException;

    boolean updateBillingPlan(SubscriptionPolicy subPolicy) throws APIManagementException;

    boolean deleteBillingPlan(SubscriptionPolicy subPolicy) throws APIManagementException;

    boolean enableMonetization(String tenantDomain, API api,
                               Map<String, String> monetizationProperties) throws APIManagementException;

    boolean disableMonetization(String tenantDomain, API api,
                                Map<String, String> monetizationProperties) throws APIManagementException;
}
