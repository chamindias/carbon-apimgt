package org.wso2.carbon.apimgt.impl.monetization;

import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.Monetization;
import org.wso2.carbon.apimgt.api.model.policy.SubscriptionPolicy;
//import org.wso2.carbon.apimgt.impl.Monetization;
import org.wso2.carbon.apimgt.api.APIManagementException;

import java.util.Map;

public class SimpleMonetizationImpl implements Monetization {

    @Override
    public boolean createBillingPlan(SubscriptionPolicy subPolicy) throws APIManagementException {
        return true;
    }

    @Override
    public boolean updateBillingPlan(SubscriptionPolicy subPolicy) throws APIManagementException {
        return true;
    }

    @Override
    public boolean deleteBillingPlan(SubscriptionPolicy subPolicy) throws APIManagementException {
        return true;
    }

    @Override
    public boolean enableMonetization(String tenantDomain, API api, Map<String, String> monetizationProperties)
            throws APIManagementException {
        return true;
    }

    @Override
    public boolean disableMonetization(String tenantDomain, API api, Map<String, String> monetizationProperties)
            throws APIManagementException {
        return true;
    }
}
