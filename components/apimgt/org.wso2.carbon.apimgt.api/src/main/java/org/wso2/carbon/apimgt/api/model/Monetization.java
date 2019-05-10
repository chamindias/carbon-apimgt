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

package org.wso2.carbon.apimgt.api.model;

import org.wso2.carbon.apimgt.api.APIManagementException;

import java.util.Map;

/**
 * Monetization interface responsible for providing helper functionality to configure monetization
 */

public interface Monetization {

    /**
     * This method is used to configure monetization for a given API
     *
     * @param monetizationStatus     monetization status
     * @param tenantDomain           tenant domain
     * @param api                    API
     * @param monetizationProperties properties related to monetization
     * @return true if monetization status changed successfully, false otherwise
     * @throws APIManagementException if failed to change the monetization status
     */
    boolean configureMonetization(boolean monetizationStatus, String tenantDomain, API api, Map<String,
            String> monetizationProperties) throws APIManagementException;

}
