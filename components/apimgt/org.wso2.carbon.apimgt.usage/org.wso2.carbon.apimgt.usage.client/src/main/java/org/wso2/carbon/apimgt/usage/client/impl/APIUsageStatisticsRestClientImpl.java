/*
* Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
* WSO2 Inc. licenses this file to you under the Apache License,
* Version 2.0 (the "License"); you may not use this file except
* in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied. See the License for the
* specific language governing permissions and limitations
* under the License.
*
*/

package org.wso2.carbon.apimgt.usage.client.impl;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.impl.builder.StAXOMBuilder;
import org.apache.axis2.client.Options;
import org.apache.axis2.client.ServiceClient;
import org.apache.axis2.transport.http.HttpTransportProperties;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONArray;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.APIProvider;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.impl.APIManagerAnalyticsConfiguration;
import org.wso2.carbon.apimgt.impl.APIManagerConfiguration;
import org.wso2.carbon.apimgt.impl.APIManagerFactory;
import org.wso2.carbon.apimgt.impl.internal.APIManagerComponent;
import org.wso2.carbon.apimgt.usage.client.APIUsageStatisticsClient;
import org.wso2.carbon.apimgt.usage.client.APIUsageStatisticsClientConstants;
import org.wso2.carbon.apimgt.usage.client.DASRestClient;
import org.wso2.carbon.apimgt.usage.client.bean.*;
import org.wso2.carbon.apimgt.usage.client.billing.APIUsageRangeCost;
import org.wso2.carbon.apimgt.usage.client.billing.PaymentPlan;
import org.wso2.carbon.apimgt.usage.client.dto.*;
import org.wso2.carbon.apimgt.usage.client.exception.APIMgtUsageQueryServiceClientException;
import org.wso2.carbon.apimgt.usage.client.internal.APIUsageClientServiceComponent;
import org.wso2.carbon.apimgt.usage.client.pojo.*;
import org.wso2.carbon.apimgt.usage.client.util.RestClientUtil;
import org.wso2.carbon.application.mgt.stub.upload.CarbonAppUploaderStub;
import org.wso2.carbon.application.mgt.stub.upload.types.carbon.UploadedFileItem;
import org.wso2.carbon.bam.service.data.publisher.conf.RESTAPIConfigData;
import org.wso2.carbon.bam.service.data.publisher.services.ServiceDataPublisherAdmin;
import org.wso2.carbon.utils.CarbonUtils;
import org.wso2.carbon.utils.multitenancy.MultitenantUtils;

import javax.activation.DataHandler;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.text.DecimalFormat;
import java.text.ParseException;
import java.util.*;

/**
 * Usage statistics clas implementation for the APIUsageStatisticsClient.
 * Use the DAS REST API to query and fetch the data for getting usage Statistics
 */
public class APIUsageStatisticsRestClientImpl extends APIUsageStatisticsClient {

    private PaymentPlan paymentPlan;
    private APIProvider apiProviderImpl;
    private static final Log log = LogFactory.getLog(APIUsageStatisticsRestClientImpl.class);
    private DASRestClient restClient;
    private final Gson gson = new Gson();

    /**
     * Create a rest client instance.
     *
     * @param username current user name
     * @throws APIMgtUsageQueryServiceClientException
     */
    public APIUsageStatisticsRestClientImpl(String username) throws APIMgtUsageQueryServiceClientException {
        OMElement element = null;
        APIManagerConfiguration config;
        APIManagerAnalyticsConfiguration apiManagerAnalyticsConfiguration;
        try {
            config = APIUsageClientServiceComponent.getAPIManagerConfiguration();
            apiManagerAnalyticsConfiguration = APIManagerAnalyticsConfiguration.getInstance();

            if (!apiManagerAnalyticsConfiguration.isAnalyticsEnabled()) {
                throw new APIMgtUsageQueryServiceClientException("Analytics not enabled");
            }

            if (restClient == null) {
                initializeDataSource();
            }
            // text = config.getFirstProperty("BillingConfig");
            String billingConfig = config.getFirstProperty("EnableBillingAndUsage");
            boolean isBillingEnabled = Boolean.parseBoolean(billingConfig);
            if (isBillingEnabled) {
                String filePath = (new StringBuilder()).append(CarbonUtils.getCarbonHome()).append(File.separator)
                        .append("repository").append(File.separator).append("conf").append(File.separator)
                        .append("billing-conf.xml").toString();
                element = buildOMElement(new FileInputStream(filePath));
                paymentPlan = new PaymentPlan(element);
            }
            String targetEndpoint = apiManagerAnalyticsConfiguration.getBamServerUrlGroups();
            if (targetEndpoint == null || targetEndpoint.equals(""))
                throw new APIMgtUsageQueryServiceClientException("Required BAM server URL parameter unspecified");
            apiProviderImpl = APIManagerFactory.getInstance().getAPIProvider(username);

        } catch (Exception e) {
            throw new APIMgtUsageQueryServiceClientException("Exception while instantiating API manager core objects",
                    e);
        }

    }/**/

    /**
     * initialize datasource of APIUsageStatisticsRestClientImpl
     *
     * @throws APIMgtUsageQueryServiceClientException
     */
    public void initializeDataSource() throws APIMgtUsageQueryServiceClientException {
        //get the config class
        ServiceDataPublisherAdmin serviceDataPublisherAdmin = APIManagerComponent.getDataPublisherAdminService();
        if (serviceDataPublisherAdmin != null) {

            //check whether analytics enable
            if (serviceDataPublisherAdmin.getEventingConfigData().isServiceStatsEnable()) {
                //get REST API config data
                RESTAPIConfigData restData = serviceDataPublisherAdmin.getRestAPIConfigData();

                String url = restData.getUrl();
                String user = restData.getUserName();
                String pass = restData.getPassword();
                //crete new restClient instance
                restClient = new DASRestClient(url, user, pass);
                log.info("Initialised DASRestClient");
            }
        }
    }

    /**
     * @param inputStream
     * @return
     * @throws Exception
     */
    public static OMElement buildOMElement(InputStream inputStream) throws Exception {
        XMLStreamReader parser;
        try {
            parser = XMLInputFactory.newInstance().createXMLStreamReader(inputStream);
        } catch (XMLStreamException e) {
            String msg = "Error in initializing the parser to build the OMElement.";
            throw new Exception(msg, e);
        } finally {
        }
        StAXOMBuilder builder = new StAXOMBuilder(parser);
        return builder.getDocumentElement();
    }

    // Store Statistic Methods

    /**
     * This method gets the API usage data per application
     *
     * @param subscriberName subscriber name
     * @param groupId        group id of the subscriber
     * @param fromDate       starting date
     * @param toDate         ending data
     * @param limit          limit of the result
     * @return return list of PerAppApiCountDTO result
     * @throws APIMgtUsageQueryServiceClientException
     */
    @Override
    public List<PerAppApiCountDTO> perAppPerAPIUsage(String subscriberName, String groupId, String fromDate,
            String toDate, int limit) throws APIMgtUsageQueryServiceClientException {

        //get list of applications
        List<String> subscriberApps = getAppsBySubscriber(subscriberName, groupId);
        String firstKey;

        //concatenation of the application keys with 'OR' to form lucene query
        int size = subscriberApps.size();
        if (size > 0) {
            firstKey = APIUsageStatisticsClientConstants.CONSUMERKEY + ':' + subscriberApps.get(0);
        } else {
            return new ArrayList<PerAppApiCountDTO>();
        }
        StringBuilder concatenatedKeys = new StringBuilder(firstKey);
        for (int i = 1; i < subscriberApps.size(); i++) {
            concatenatedKeys
                    .append(" OR " + APIUsageStatisticsClientConstants.CONSUMERKEY + ':' + subscriberApps.get(i));
        }

        //get the usage result
        List<PerAppApiCountDTO> usage = getPerAppAPIUsageData(APIUsageStatisticsClientConstants.API_REQUEST_SUMMARY,
                concatenatedKeys.toString(), fromDate, toDate, limit);
        return usage;

    }

    /**
     * This method gets the API usage data per application data
     *
     * @param tableName name of the required table
     * @param keyString concatenated key set of applications
     * @param fromDate  starting date of the results
     * @param toDate    ending date of the results
     * @param limit     limit of the results
     * @return a list containing the data related to per App API usage
     * @throws APIMgtUsageQueryServiceClientException if an error occurs while querying the database
     */
    private List<PerAppApiCountDTO> getPerAppAPIUsageData(String tableName, String keyString, String fromDate,
            String toDate, int limit) throws APIMgtUsageQueryServiceClientException {
        //limit is used after DAS provide pagination of aggregate search

        String query = null;
        //extending lucene query with time ranges
        try {
            query = "max_request_time: [" + RestClientUtil.dateToLong(fromDate) + " TO " + RestClientUtil
                    .dateToLong(toDate) + "] AND ( " + keyString + " )";
        } catch (ParseException e) {
            handleException("Error occurred while Error parsing date", e);
        }

        //creating request bean
        SearchRequestBean request = new SearchRequestBean(query, 1, "key_api_facet", tableName);
        ArrayList<AggregateField> fields = new ArrayList<AggregateField>();
        AggregateField f = new AggregateField("total_request_count", "SUM", "count");
        fields.add(f);
        request.setAggregateFields(fields);

        //get the type of the required result type
        Type type = new TypeToken<List<Result<PerAppPerAPIUsageValues>>>() {
        }.getType();

        List<Result<PerAppPerAPIUsageValues>> obj = null;

        //do post and get the results
        try {
            obj = restClient.doPost(request, type);
        } catch (JsonSyntaxException e) {
            handleException("Error occurred while parsing response", e);
        } catch (IOException e) {
            handleException("Error occurred while Connecting to DAS REST API", e);
        }

        List<PerAppApiCountDTO> perAppUsageDataList = new ArrayList<PerAppApiCountDTO>();

        //check the result status
        if (obj == null || obj.isEmpty()) {
            return perAppUsageDataList;
        }

        PerAppApiCountDTO apiUsageDTO;
        //add the list of apis to the app
        for (Result<PerAppPerAPIUsageValues> result : obj) {
            PerAppPerAPIUsageValues values = result.getValues();

            //getColumnNames 0 index contain the app key and 1 index contain the api name

            String appName = subscriberAppsMap.get(values.getColumnNames().get(0));

            boolean found = false;
            for (PerAppApiCountDTO dto : perAppUsageDataList) {
                if (dto.getAppName().equals(appName)) {
                    dto.addToApiCountArray(values.getColumnNames().get(1), values.getCount_sum());
                    found = true;
                    break;
                }
            }

            if (!found) {
                apiUsageDTO = new PerAppApiCountDTO();
                apiUsageDTO.setAppName(appName);
                apiUsageDTO.addToApiCountArray(values.getColumnNames().get(1), values.getCount_sum());
                perAppUsageDataList.add(apiUsageDTO);
            }

        }

        return perAppUsageDataList;
    }

    /**
     * This method gets the app users stat for invoking APIs
     *
     * @param subscriberName subscriber name
     * @param groupId        group id of the subscriber
     * @param fromDate       starting date
     * @param toDate         ending data
     * @param limit          limit of the result
     * @return return list of AppUsageDTO result
     * @throws APIMgtUsageQueryServiceClientException
     */
    @Override
    public List<AppUsageDTO> getTopAppUsers(String subscriberName, String groupId, String fromDate, String toDate,
            int limit) throws APIMgtUsageQueryServiceClientException {

        //get list of applications
        List<String> subscriberApps = getAppsBySubscriber(subscriberName, groupId);
        String firstKey;

        //concatenation of the application keys with 'OR' to form lucene query
        int size = subscriberApps.size();
        if (size > 0) {
            firstKey = APIUsageStatisticsClientConstants.CONSUMERKEY + ":" + subscriberApps.get(0);
        } else {
            return new ArrayList<AppUsageDTO>();
        }
        StringBuilder concatenatedKeys = new StringBuilder(firstKey);
        for (int i = 1; i < subscriberApps.size(); i++) {
            concatenatedKeys
                    .append(" OR " + APIUsageStatisticsClientConstants.CONSUMERKEY + ':' + subscriberApps.get(i));
        }

        //get the usage result
        List<AppUsageDTO> usage = getTopAppUsageData(APIUsageStatisticsClientConstants.API_REQUEST_SUMMARY,
                concatenatedKeys.toString(), fromDate, toDate, limit);
        return usage;

    }

    /**
     * This method gets the app usage data for invoking APIs
     *
     * @param tableName name of the required table
     * @param keyString concatenated key set of applications
     * @param fromDate  starting date of the results
     * @param toDate    ending date of the results
     * @param limit     limit of the results
     * @return a List containing the data related to App usage
     * @throws APIMgtUsageQueryServiceClientException
     */
    private List<AppUsageDTO> getTopAppUsageData(String tableName, String keyString, String fromDate, String toDate,
            int limit) throws APIMgtUsageQueryServiceClientException {
        //limit is used after DAS provide pagination of aggregate search
        String query = null;
        //extending lucene query with time ranges
        try {
            query = "max_request_time: [" + RestClientUtil.dateToLong(fromDate) + " TO " + RestClientUtil
                    .dateToLong(toDate) + "] AND ( " + keyString + " )";
        } catch (ParseException e) {
            handleException("Error occurred while Error parsing date", e);
        }

        //creating request bean
        SearchRequestBean request = new SearchRequestBean(query, 1, "key_userId_facet", tableName);

        ArrayList<AggregateField> fields = new ArrayList<AggregateField>();
        AggregateField field = new AggregateField("total_request_count", "SUM", "count");
        fields.add(field);
        request.setAggregateFields(fields);

        Type type = new TypeToken<List<Result<TopAppUsersValues>>>() {
        }.getType();

        List<Result<TopAppUsersValues>> obj = null;

        //do post and get the results
        try {
            obj = restClient.doPost(request, type);
        } catch (JsonSyntaxException e) {
            handleException("Error occurred while parsing response", e);
        } catch (IOException e) {
            handleException("Error occurred while Connecting to DAS REST API", e);
        }

        List<AppUsageDTO> topAppUsageDataList = new ArrayList<AppUsageDTO>();

        //check the result status
        if (obj == null || obj.isEmpty()) {
            return topAppUsageDataList;
        }

        AppUsageDTO appUsageDTO;
        //add the list of apis to the app
        for (Result<TopAppUsersValues> result : obj) {
            TopAppUsersValues v = result.getValues();

            //getColumnNames 0 index contain the app key and 1 index contain the userId
            String appName = subscriberAppsMap.get(v.getColumnNames().get(0));

            boolean found = false;
            for (AppUsageDTO dto : topAppUsageDataList) {
                if (dto.getAppName().equals(appName)) {
                    dto.addToUserCountArray(v.getColumnNames().get(1), v.getCount());
                    found = true;
                    break;
                }
            }

            if (!found) {
                appUsageDTO = new AppUsageDTO();
                appUsageDTO.setAppName(appName);
                appUsageDTO.addToUserCountArray(v.getColumnNames().get(1), v.getCount());
                topAppUsageDataList.add(appUsageDTO);
            }

        }

        return topAppUsageDataList;
    }

    /**
     * @param subscriberName subscriber name
     * @param groupId        group id of the subscriber
     * @param fromDate       starting date
     * @param toDate         ending data
     * @param limit          limit of the result
     * @return list of AppCallTypeDTO result
     * @throws APIMgtUsageQueryServiceClientException
     */
    @Override
    public List<AppCallTypeDTO> getAppApiCallType(String subscriberName, String groupId, String fromDate, String toDate,
            int limit) throws APIMgtUsageQueryServiceClientException {

        //get list of applications
        List<String> subscriberApps = getAppsBySubscriber(subscriberName, groupId);
        String firstKey;

        //concatenation of the application keys with 'OR' to form lucene query
        int size = subscriberApps.size();
        if (size > 0) {
            firstKey = APIUsageStatisticsClientConstants.CONSUMERKEY + ":" + subscriberApps.get(0);
        } else {
            return new ArrayList<AppCallTypeDTO>();
        }
        StringBuilder concatenatedKeys = new StringBuilder(firstKey);
        for (int i = 1; i < subscriberApps.size(); i++) {
            concatenatedKeys
                    .append(" OR " + APIUsageStatisticsClientConstants.CONSUMERKEY + ':' + subscriberApps.get(i));
        }

        //get the usage result
        List<AppCallTypeDTO> usage = getAPICallTypeUsageData(
                APIUsageStatisticsClientConstants.API_Resource_Path_USAGE_SUMMARY, concatenatedKeys.toString(),
                fromDate, toDate, limit);

        return usage;
    }

    /**
     * This method gets the API usage data per API call type
     *
     * @param tableName name of the required table
     * @param keyString concatenated key set of applications
     * @param fromDate  starting date of the results
     * @param toDate    ending date of the results
     * @param limit     limit of the results
     * @return a List containing the data related to API call types
     * @throws APIMgtUsageQueryServiceClientException
     */
    private List<AppCallTypeDTO> getAPICallTypeUsageData(String tableName, String keyString, String fromDate,
            String toDate, int limit) throws APIMgtUsageQueryServiceClientException {
        //limit is used after DAS provide pagination of aggregate search
        String query = null;

        //extending lucene query with time ranges
        try {
            query = "max_request_time: [" + RestClientUtil.dateToLong(fromDate) + " TO " + RestClientUtil
                    .dateToLong(toDate) + "] AND ( " + keyString + " )";
        } catch (ParseException e) {
            handleException("Error occurred while Error parsing date", e);
        }

        //creating request bean
        SearchRequestBean request = new SearchRequestBean(query, 3, "key_api_method_path_facet", tableName);
        ArrayList<AggregateField> fields = new ArrayList<AggregateField>();
        AggregateField field = new AggregateField("total_request_count", "SUM", "count");
        fields.add(field);
        request.setAggregateFields(fields);

        //get the type of the required result type
        Type type = new TypeToken<List<Result<AppApiCallTypeValues>>>() {
        }.getType();

        List<Result<AppApiCallTypeValues>> obj = null;
        //do post and get the results
        try {
            obj = restClient.doPost(request, type);
        } catch (JsonSyntaxException e) {
            handleException("Error occurred while parsing response", e);
        } catch (IOException e) {
            handleException("Error occurred while Connecting to DAS REST API", e);
        }

        List<AppCallTypeDTO> appApiCallTypeList = new ArrayList<AppCallTypeDTO>();

        if (obj == null || obj.isEmpty()) {
            return appApiCallTypeList;
        }

        AppCallTypeDTO appCallTypeDTO;
        //add the list of apis to the app
        for (Result<AppApiCallTypeValues> result : obj) {
            AppApiCallTypeValues v = result.getValues();

            List<String> callTypeList = new ArrayList<String>();
            callTypeList.add(v.getColumnNames().get(3) + " (" + v.getColumnNames().get(2) + ")");

            //getColumnNames 0 index contain the app key and 1 index contain the api name, 2 index contain method, 3 index contain path
            String appName = subscriberAppsMap.get(v.getColumnNames().get(0));

            boolean found = false;
            for (AppCallTypeDTO dto : appApiCallTypeList) {
                if (dto.getAppName().equals(appName)) {
                    dto.addGToApiCallTypeArray(v.getColumnNames().get(1), callTypeList);
                    found = true;
                    break;
                }
            }

            if (!found) {
                appCallTypeDTO = new AppCallTypeDTO();
                appCallTypeDTO.setAppName(appName);
                appCallTypeDTO.addGToApiCallTypeArray(v.getColumnNames().get(1), callTypeList);
                appApiCallTypeList.add(appCallTypeDTO);
            }

        }

        return appApiCallTypeList;
    }

    /**
     * @param subscriberName subscriber name
     * @param groupId        group id of the subscriber
     * @param fromDate       starting date
     * @param toDate         ending data
     * @param limit          limit of the result
     * @return list of FaultCountDTO result
     * @throws APIMgtUsageQueryServiceClientException
     */
    @Override
    public List<FaultCountDTO> getPerAppAPIFaultCount(String subscriberName, String groupId, String fromDate,
            String toDate, int limit) throws APIMgtUsageQueryServiceClientException {
        //get list of applications
        List<String> subscriberApps = getAppsBySubscriber(subscriberName, groupId);
        String firstKey;

        //concatenation of the application keys with 'OR' to form lucene query
        int size = subscriberApps.size();
        if (size > 0) {
            firstKey = APIUsageStatisticsClientConstants.CONSUMERKEY + ":" + subscriberApps.get(0);
        } else {
            return new ArrayList<FaultCountDTO>();
        }
        StringBuilder concatenatedKeys = new StringBuilder(firstKey);
        for (int i = 1; i < subscriberApps.size(); i++) {
            concatenatedKeys
                    .append(" OR " + APIUsageStatisticsClientConstants.CONSUMERKEY + ':' + subscriberApps.get(i));
        }

        //get the usage result
        List<FaultCountDTO> usage = getFaultAppUsageData(APIUsageStatisticsClientConstants.API_FAULT_SUMMARY,
                concatenatedKeys.toString(), fromDate, toDate, limit);
        return usage;
    }

    /**
     * This method gets the API faulty invocation data
     *
     * @param tableName name of the required table
     * @param keyString concatenated key set of applications
     * @param fromDate  starting date of the results
     * @param toDate    ending date of the results
     * @param limit     limit of the results
     * @return a List the data related to API faulty invocations
     * @throws APIMgtUsageQueryServiceClientException
     */
    private List<FaultCountDTO> getFaultAppUsageData(String tableName, String keyString, String fromDate, String toDate,
            int limit) throws APIMgtUsageQueryServiceClientException {
        //limit is used after DAS provide pagination of aggregate search
        String query = null;

        //extending lucene query with time ranges
        try {
            query = "max_request_time: [" + RestClientUtil.dateToLong(fromDate) + " TO " + RestClientUtil
                    .dateToLong(toDate) + "] AND ( " + keyString + " )";
        } catch (ParseException e) {
            handleException("Error occurred while Error parsing date", e);
        }

        //creating request bean
        SearchRequestBean request = new SearchRequestBean(query, 1, "consumerKey_api_facet", "API_FAULT_SUMMARY");
        ArrayList<AggregateField> fields = new ArrayList<AggregateField>();
        AggregateField field = new AggregateField("total_fault_count", "SUM", "count");
        fields.add(field);
        request.setAggregateFields(fields);

        //get the type of the required result type
        Type type = new TypeToken<List<Result<FaultAppUsageDataValue>>>() {
        }.getType();

        List<Result<FaultAppUsageDataValue>> obj = null;

        //do post and get the results
        try {
            obj = restClient.doPost(request, type);
        } catch (JsonSyntaxException e) {
            handleException("Error occurred while parsing response", e);
        } catch (IOException e) {
            handleException("Error occurred while Connecting to DAS REST API", e);
        }

        List<FaultCountDTO> falseAppUsageDataList = new ArrayList<FaultCountDTO>();

        //check the result status
        if (obj == null || obj.isEmpty()) {
            return falseAppUsageDataList;
        }

        //get the POJO class from the response bean classes
        //getColumnNames 0 index contain the api keye, index 1 contain the api name
        FaultCountDTO faultCountDTO;
        //add the list of apis to the app
        for (Result<FaultAppUsageDataValue> result : obj) {
            FaultAppUsageDataValue v = result.getValues();

            String appName = subscriberAppsMap.get(v.getColumnNames().get(0));
            String apiName = v.getColumnNames().get(1);

            boolean found = false;
            for (FaultCountDTO dto : falseAppUsageDataList) {
                if (dto.getAppName().equals(appName)) {
                    dto.addToApiFaultCountArray(apiName, v.getCount());
                    found = true;
                    break;
                }
            }

            if (!found) {
                faultCountDTO = new FaultCountDTO();
                faultCountDTO.setAppName(appName);
                faultCountDTO.addToApiFaultCountArray(apiName, v.getCount());
                falseAppUsageDataList.add(faultCountDTO);
            }

        }

        return falseAppUsageDataList;
    }

    // Publisher Statistic Methods

    /**
     * Returns a list of APIUsageByUserDTO objects that contain information related to
     * User wise API Usage, along with the number of invocations, and API Version
     *
     * @param providerName Name of the API provider
     * @param fromDate     starting date of the results
     * @param toDate       ending date of the results
     * @return list of APIUsageByUserDTO
     * @throws APIMgtUsageQueryServiceClientException
     */
    @Override
    public List<APIUsageByUserDTO> getAPIUsageByUser(String providerName, String fromDate, String toDate)
            throws APIMgtUsageQueryServiceClientException {

        //get the list of apis usage by user
        List<APIUsageByUserName> usageData = this.getAPIUsageByUserData(providerName, fromDate, toDate, null);

        //get the tenant domain of the provider
        String tenantDomain = MultitenantUtils.getTenantDomain(providerName);

        List<APIUsageByUserDTO> usageByName = new ArrayList<APIUsageByUserDTO>();

        for (APIUsageByUserName usage : usageData) {
            //add the result to final list, if the api publisher is part of the provider tenant
            if (tenantDomain.equals(MultitenantUtils.getTenantDomain(usage.getApipublisher()))) {
                APIUsageByUserDTO usageDTO = new APIUsageByUserDTO();
                usageDTO.setApiName(usage.getApiName());
                usageDTO.setVersion(usage.getApiVersion());
                usageDTO.setUserID(usage.getUserID());
                usageDTO.setCount(usage.getRequestCount());
                usageByName.add(usageDTO);
            }
        }
        return usageByName;
    }

    /**
     * search the User wise API Usage data and provide as a list of APIUsageByUserName
     *
     * @param providerName Name of the API provider
     * @param fromDate     starting date of the results
     * @param toDate       ending date of the results
     * @param limit        limit of the result
     * @return list of APIUsageByUserName
     * @throws APIMgtUsageQueryServiceClientException
     */
    private List<APIUsageByUserName> getAPIUsageByUserData(String providerName, String fromDate, String toDate,
            Integer limit) throws APIMgtUsageQueryServiceClientException {
        //limit is used after DAS provide pagination of aggregate search

        String query = null;
        //extending lucene query with time ranges
        try {
            query = "max_request_time: [" + RestClientUtil.getFloorDateAsLong(fromDate) + " TO " + RestClientUtil
                    .getCeilingDateAsLong(toDate) + "]";
        } catch (ParseException e) {
            handleException("Error occurred while Error parsing date", e);
        }

        //creating request bean
        SearchRequestBean request = new SearchRequestBean(query, 3, "api_version_userId_apiPublisher_facet",
                "API_REQUEST_SUMMARY");

        ArrayList<AggregateField> fields = new ArrayList<AggregateField>();
        AggregateField field = new AggregateField("total_request_count", "SUM", "count");
        fields.add(field);
        request.setAggregateFields(fields);

        //get the type of the required result type
        Type type = new TypeToken<List<Result<APIUsageByUserValues>>>() {
        }.getType();

        List<Result<APIUsageByUserValues>> obj = null;

        //do post and get the results
        try {
            obj = restClient.doPost(request, type);
        } catch (JsonSyntaxException e) {
            handleException("Error occurred while parsing response", e);
        } catch (IOException e) {
            handleException("Error occurred while Connecting to DAS REST API", e);
        }

        List<APIUsageByUserName> usageByName = new ArrayList<APIUsageByUserName>();

        //check the result status
        if (obj == null || obj.isEmpty()) {
            return usageByName;
        }

        //get the POJO class from the response bean classes
        //getColumnNames 0 index contain the api name, index 1 contain the version, 2 index contain userId, 3 index contain publisher
        APIUsageByUserName usage;
        for (Result<APIUsageByUserValues> result : obj) {
            APIUsageByUserValues v = result.getValues();

            usage = new APIUsageByUserName();
            usage.setRequestCount(v.getCount_sum());

            usage.setApiName(v.getColumnNames().get(0));
            usage.setApiVersion(v.getColumnNames().get(1));
            usage.setUserID(v.getColumnNames().get(2));
            usage.setApipublisher(v.getColumnNames().get(3));

            usageByName.add(usage);
        }

        return usageByName;

    }

    /**
     * Gets a list of APIResponseTimeDTO objects containing information related to APIs belonging
     * to a particular provider along with their average response times.
     *
     * @param providerName Name of the API provider
     * @param fromDate     starting date of the results
     * @param toDate       ending date of the results
     * @param limit        limit of the result
     * @return a List of APIResponseTimeDTO objects, possibly empty
     * @throws APIMgtUsageQueryServiceClientException
     */
    @Override
    public List<APIResponseTimeDTO> getProviderAPIServiceTime(String providerName, String fromDate, String toDate,
            int limit) throws APIMgtUsageQueryServiceClientException {

        //get the response time data of the apis
        List<APIResponseTime> responseTimes = getAPIResponseTimeData(
                APIUsageStatisticsClientConstants.API_VERSION_SERVICE_TIME_SUMMARY, fromDate, toDate, limit);

        //get all the apis of the provider
        List<API> providerAPIs = getAPIsByProvider(providerName);
        //        Map<String, Double> apiCumulativeServiceTimeMap = new HashMap<String, Double>();
        //        Map<String, Long> apiUsageMap = new TreeMap<String, Long>();
        DecimalFormat format = new DecimalFormat("#.##");
        List<APIResponseTimeDTO> apiResponseTimeUsage = new ArrayList<APIResponseTimeDTO>();

        //iterate over all the result data
        for (APIResponseTime responseTime : responseTimes) {
            //iterate over all the apis of the provider
            for (API providerAPI : providerAPIs) {
                //consider the result if, api is part of the provider's api list
                if (providerAPI.getId().getApiName().equals(responseTime.getApiName()) &&
                        providerAPI.getId().getVersion().equals(responseTime.getApiVersion()) &&
                        providerAPI.getContext().equals(responseTime.getContext())) {

                    //commented old code for re-validating new cde
                    /*String apiName = responseTime.apiName + " (" + providerAPI.getId().getProviderName() + ")";
                    Double cumulativeResponseTime = apiCumulativeServiceTimeMap.get(apiName);

                    if (cumulativeResponseTime != null) {
                        apiCumulativeServiceTimeMap.put(apiName,
                                cumulativeResponseTime + responseTime.responseTime * responseTime.responseCount);
                        apiUsageMap.put(apiName,
                                apiUsageMap.get(apiName) + responseTime.responseCount);
                    } else {
                        apiCumulativeServiceTimeMap.put(apiName,
                                responseTime.responseTime * responseTime.responseCount);
                        apiUsageMap.put(apiName, responseTime.responseCount);
                    }*/

                    APIResponseTimeDTO responseTimeDTO = new APIResponseTimeDTO();
                    responseTimeDTO.setApiName(responseTime.getApiName());

                    //calculate the average response time
                    double avgTime = responseTime.getResponseTime() / responseTime.getResponseCount();
                    //format the time
                    responseTimeDTO.setServiceTime(Double.parseDouble(format.format(avgTime)));
                    apiResponseTimeUsage.add(responseTimeDTO);
                }
            }
        }

        //commented old code for re-validating new cde

        /*Map<String, APIResponseTimeDTO> responseTimeByAPI = new TreeMap<String, APIResponseTimeDTO>();
        DecimalFormat format = new DecimalFormat("#.##");
        for (String key : apiUsageMap.keySet()) {
            APIResponseTimeDTO responseTimeDTO = new APIResponseTimeDTO();
            responseTimeDTO.setApiName(key);
            double responseTime = apiCumulativeServiceTimeMap.get(key) / apiUsageMap.get(key);
            responseTimeDTO.setServiceTime(Double.parseDouble(format.format(responseTime)));
            responseTimeByAPI.put(key, responseTimeDTO);
        }
        return getResponseTimeTopEntries(new ArrayList<APIResponseTimeDTO>(responseTimeByAPI.values()), limit);*/
        return apiResponseTimeUsage;
    }

    /**
     * This method gets the response times for APIs
     *
     * @param tableName name of the required table in the database
     * @return a collection containing the data related to API response times
     * @throws APIMgtUsageQueryServiceClientException if an error occurs while querying the database
     */
    private List<APIResponseTime> getAPIResponseTimeData(String tableName, String fromDate, String toDate, int limit)
            throws APIMgtUsageQueryServiceClientException {
        //limit is used after DAS provide pagination of aggregate search
        String query = null;

        //extending lucene query with time ranges
        try {
            query = "max_request_time: [" + RestClientUtil.getFloorDateAsLong(fromDate) + " TO " + RestClientUtil
                    .getCeilingDateAsLong(toDate) + "]";
        } catch (ParseException e) {
            handleException("Error occurred while Error parsing date", e);
        }

        //creating request bean
        SearchRequestBean request = new SearchRequestBean(query, 1, "api_version_context_facet",
                "API_RESPONSE_SUMMARY");

        ArrayList<AggregateField> fields = new ArrayList<AggregateField>();
        AggregateField field0 = new AggregateField("serviceTime", "SUM", "totalServiceTime");
        AggregateField field1 = new AggregateField("total_response_count", "SUM", "totalResponseCount");
        fields.add(field0);
        fields.add(field1);
        request.setAggregateFields(fields);

        //get the type of the required result type
        Type type = new TypeToken<List<Result<ResponseTimesByAPIsValue>>>() {
        }.getType();

        List<Result<ResponseTimesByAPIsValue>> obj = null;

        //do post and get the results
        try {
            obj = restClient.doPost(request, type);
        } catch (JsonSyntaxException e) {
            handleException("Error occurred while parsing response", e);
        } catch (IOException e) {
            handleException("Error occurred while Connecting to DAS REST API", e);
        }

        List<APIResponseTime> responseTimeData = new ArrayList<APIResponseTime>();

        //check the result status
        if (obj == null || obj.isEmpty()) {
            return responseTimeData;
        }

        //get the POJO class from the response bean classes
        //getColumnNames 0 index contain the api_version name, index 1 contain context
        APIResponseTime usage;
        for (Result<ResponseTimesByAPIsValue> result : obj) {
            ResponseTimesByAPIsValue v = result.getValues();

            usage = new APIResponseTime();
            usage.setApiName(v.getColumnNames().get(0).split(":v")[0]);
            usage.setApiVersion(v.getColumnNames().get(0).split(":v")[1]);
            usage.setContext(v.getColumnNames().get(1));
            usage.setResponseTime(v.getTotalServiceTime());
            usage.setResponseCount(v.getTotalResponseCount());

            responseTimeData.add(usage);
        }

        return responseTimeData;
    }

    /**
     * Returns a list of APIVersionLastAccessTimeDTO objects for all the APIs belonging to the
     * specified provider. Last access times are calculated without taking API versions into
     * account. That is all the versions of an API are treated as one.
     *
     * @param providerName Name of the API provider
     * @param fromDate     starting date of the results
     * @param toDate       ending date of the results
     * @param limit        limit of the result
     * @return a list of APIVersionLastAccessTimeDTO objects, possibly empty
     * @throws APIMgtUsageQueryServiceClientException
     */
    @Override
    public List<APIVersionLastAccessTimeDTO> getProviderAPIVersionUserLastAccess(String providerName, String fromDate,
            String toDate, int limit) throws APIMgtUsageQueryServiceClientException {

        //get the last access time data of the apis
        List<APIAccessTime> accessTimes = getLastAccessTimesByAPIData(
                APIUsageStatisticsClientConstants.API_VERSION_KEY_LAST_ACCESS_SUMMARY, fromDate, toDate, limit);

        //get all the apis of the provider
        List<API> providerAPIs = getAPIsByProvider(providerName);
        List<APIVersionLastAccessTimeDTO> apiVersionLastAccessTimeUsage = new ArrayList<APIVersionLastAccessTimeDTO>();

        APIVersionLastAccessTimeDTO accessTimeDTO;
        String apiName;

        //iterate over all the result data
        for (APIAccessTime accessTime : accessTimes) {
            //iterate over all the apis of the provider
            for (API providerAPI : providerAPIs) {
                //consider the result if, api is part of the provider's api list
                if (providerAPI.getId().getApiName().equals(accessTime.getApiName()) &&
                        providerAPI.getId().getVersion().equals(accessTime.getApiVersion()) &&
                        providerAPI.getContext().equals(accessTime.getContext())) {

                    //commented old code for re-validating new cde
                    /*String apiName = accessTime.apiName + " (" + providerAPI.getId().getProviderName() + ")";
                    APIAccessTime lastAccessTime = lastAccessTimes.get(apiName);
                    if (lastAccessTime == null || lastAccessTime.accessTime < accessTime.accessTime) {
                        lastAccessTimes.put(apiName, accessTime);
                        break;
                    }*/
                    //format api name with provider name
                    apiName = accessTime.getApiName() + " (" + providerAPI.getId().getProviderName() + ")";
                    accessTimeDTO = new APIVersionLastAccessTimeDTO();
                    accessTimeDTO.setApiName(apiName);
                    accessTimeDTO.setApiVersion(accessTime.getApiVersion());
                    accessTimeDTO.setUser(accessTime.getUsername());
                    accessTimeDTO.setLastAccessTime(accessTime.getAccessTime() + "");
                    apiVersionLastAccessTimeUsage.add(accessTimeDTO);
                }
            }
        }

        //commented old code for re-validating new cde
        /*Map<String, APIVersionLastAccessTimeDTO> accessTimeByAPI = new TreeMap<String, APIVersionLastAccessTimeDTO>();
        List<APIVersionLastAccessTimeDTO> accessTimeDTOs = new ArrayList<APIVersionLastAccessTimeDTO>();
        DateFormat dateFormat = new SimpleDateFormat();
        for (Map.Entry<String, APIAccessTime> entry : lastAccessTimes.entrySet()) {
            APIVersionLastAccessTimeDTO accessTimeDTO = new APIVersionLastAccessTimeDTO();
            accessTimeDTO.setApiName(entry.getKey());
            APIAccessTime lastAccessTime = entry.getValue();
            accessTimeDTO.setApiVersion(lastAccessTime.apiVersion);
            accessTimeDTO.setLastAccessTime(dateFormat.format(lastAccessTime.accessTime));
            accessTimeDTO.setUser(lastAccessTime.username);
            accessTimeByAPI.put(entry.getKey(), accessTimeDTO);
        }
        return getLastAccessTimeTopEntries(new ArrayList<APIVersionLastAccessTimeDTO>(accessTimeByAPI.values()), limit);*/
        return apiVersionLastAccessTimeUsage;
    }

    /**
     * This method gets the last access times for APIs
     *
     * @param tableName name of the required table in the database
     * @return a collection containing the data related to API last access times
     * @throws APIMgtUsageQueryServiceClientException if an error occurs while querying the database
     */
    private List<APIAccessTime> getLastAccessTimesByAPIData(String tableName, String fromDate, String toDate, int limit)
            throws APIMgtUsageQueryServiceClientException {
        //limit is used after DAS provide pagination of aggregate search

        String query = null;

        //extending lucene query with time ranges
        try {
            query = "max_request_time: [" + RestClientUtil.getFloorDateAsLong(fromDate) + " TO " + RestClientUtil
                    .getCeilingDateAsLong(toDate) + "]";
        } catch (ParseException e) {
            handleException("Error occurred while Error parsing date", e);
        }

        //creating request bean
        SearchRequestBean request = new SearchRequestBean(query, 3, "api_version_userId_context_facet",
                "API_REQUEST_SUMMARY");

        ArrayList<AggregateField> fields = new ArrayList<AggregateField>();
        AggregateField field = new AggregateField("max_request_time", "MAX", "lastAccessTime");
        fields.add(field);
        request.setAggregateFields(fields);

        //get the type of the required result type
        Type type = new TypeToken<List<Result<LastAccessTimesByAPIValue>>>() {
        }.getType();

        List<Result<LastAccessTimesByAPIValue>> obj = null;

        //do post and get the results
        try {
            obj = restClient.doPost(request, type);
        } catch (JsonSyntaxException e) {
            handleException("Error occurred while parsing response", e);
        } catch (IOException e) {
            handleException("Error occurred while Connecting to DAS REST API", e);
        }

        List<APIAccessTime> lastAccessTimeData = new ArrayList<APIAccessTime>();

        //check the result status
        if (obj == null || obj.isEmpty()) {
            return lastAccessTimeData;
        }

        //get the POJO class from the response bean classes
        //getColumnNames 0 index contain the api name, index 1 contain the version, 2 index contain userId, 3 index contain context
        APIAccessTime usage;
        for (Result<LastAccessTimesByAPIValue> result : obj) {
            LastAccessTimesByAPIValue v = result.getValues();

            usage = new APIAccessTime();
            usage.setAccessTime(v.getLastAccessTime());
            usage.setApiName(v.getColumnNames().get(0));
            usage.setApiVersion(v.getColumnNames().get(1));
            usage.setUsername(v.getColumnNames().get(2));
            usage.setContext(v.getColumnNames().get(3));
            lastAccessTimeData.add(usage);
        }

        return lastAccessTimeData;

    }

    /**
     * Returns a list of APIVersionUsageDTO objects that contain information related to a
     * particular API of a specified provider, along with the number of API calls processed
     * by each resource path of that API.
     *
     * @param providerName Name of the API provider
     * @param fromDate     starting date of the results
     * @param toDate       ending date of the results
     * @return a List of APIResourcePathUsageDTO objects, possibly empty
     * @throws APIMgtUsageQueryServiceClientException
     */
    @Override
    public List<APIResourcePathUsageDTO> getAPIUsageByResourcePath(String providerName, String fromDate, String toDate)
            throws APIMgtUsageQueryServiceClientException {

        //get the usage resource path data of the apis
        List<APIUsageByResourcePath> usageData = this
                .getAPIUsageByResourcePathData(APIUsageStatisticsClientConstants.API_Resource_Path_USAGE_SUMMARY,
                        fromDate, toDate);

        //get all the apis of the provider
        List<API> providerAPIs = getAPIsByProvider(providerName);
        List<APIResourcePathUsageDTO> usageByResourcePath = new ArrayList<APIResourcePathUsageDTO>();

        //iterate over all the result data
        for (APIUsageByResourcePath usage : usageData) {
            //iterate over all the apis of the provider
            for (API providerAPI : providerAPIs) {
                //consider the result if, api is part of the provider's api list
                if (providerAPI.getId().getApiName().equals(usage.getApiName()) &&
                        providerAPI.getId().getVersion().equals(usage.getApiVersion()) &&
                        providerAPI.getContext().equals(usage.getContext())) {

                    APIResourcePathUsageDTO usageDTO = new APIResourcePathUsageDTO();
                    usageDTO.setApiName(usage.getApiName());
                    usageDTO.setVersion(usage.getApiVersion());
                    usageDTO.setMethod(usage.getMethod());
                    usageDTO.setContext(usage.getContext());
                    usageDTO.setCount(usage.getRequestCount());
                    usageDTO.setTime(usage.getTime());
                    usageByResourcePath.add(usageDTO);
                }
            }
        }
        return usageByResourcePath;
    }

    /**
     * search and return the resource path related data
     *
     * @param tableName name of the table in the database
     * @param fromDate  starting date of the results
     * @param toDate    ending date of the results
     * @return a collection containing the API usage data of resource path
     * @throws APIMgtUsageQueryServiceClientException
     */
    private List<APIUsageByResourcePath> getAPIUsageByResourcePathData(String tableName, String fromDate, String toDate)
            throws APIMgtUsageQueryServiceClientException {
        //limit is used after DAS provide pagination of aggregate search

        String query = null;

        //extending lucene query with time ranges
        try {
            query = "max_request_time: [" + RestClientUtil.getFloorDateAsLong(fromDate) + " TO " + RestClientUtil
                    .getCeilingDateAsLong(toDate) + "]";
        } catch (ParseException e) {
            handleException("Error occurred while Error parsing date", e);
        }

        //creating request bean
        SearchRequestBean request = new SearchRequestBean(query, 3, "api_version_context_method_facet",
                "API_RESOURCE_USAGE_SUMMARY");

        ArrayList<AggregateField> fields = new ArrayList<AggregateField>();
        AggregateField field = new AggregateField("total_request_count", "SUM", "totalRequestCount");
        fields.add(field);
        request.setAggregateFields(fields);

        //get the type of the required result type
        Type type = new TypeToken<List<Result<APIUsageByResourcePathValue>>>() {
        }.getType();

        List<Result<APIUsageByResourcePathValue>> obj = null;

        //do post and get the results
        try {
            obj = restClient.doPost(request, type);
        } catch (JsonSyntaxException e) {
            handleException("Error occurred while parsing response", e);
        } catch (IOException e) {
            handleException("Error occurred while Connecting to DAS REST API", e);
        }

        List<APIUsageByResourcePath> usageByResourcePath = new ArrayList<APIUsageByResourcePath>();

        //check the result status
        if (obj == null || obj.isEmpty()) {
            return usageByResourcePath;
        }

        //get the POJO class from the response bean classes
        //getColumnNames 0 index contain the api name, index 1 contain the version, 2 index contain context, 3 index contain method
        APIUsageByResourcePath usage;
        for (Result<APIUsageByResourcePathValue> result : obj) {
            APIUsageByResourcePathValue v = result.getValues();

            usage = new APIUsageByResourcePath();
            usage.setRequestCount(v.getTotalRequestCount());
            usage.setApiName(v.getColumnNames().get(0));
            usage.setApiVersion(v.getColumnNames().get(1));
            usage.setContext(v.getColumnNames().get(2));
            usage.setMethod(v.getColumnNames().get(3));

            usageByResourcePath.add(usage);
        }

        return usageByResourcePath;

    }

    /**
     * Gets a list of APIDestinationUsageDTO objects containing information related to APIs belonging
     * to a particular provider
     *
     * @param providerName Name of the API provider
     * @param fromDate     starting date of the results
     * @param toDate       ending date of the results
     * @return a List of APIDestinationUsageDTO objects, possibly empty
     * @throws APIMgtUsageQueryServiceClientException
     */
    @Override
    public List<APIDestinationUsageDTO> getAPIUsageByDestination(String providerName, String fromDate, String toDate)
            throws APIMgtUsageQueryServiceClientException {

        //get the destination data of the apis
        List<APIUsageByDestination> usageData = this
                .getAPIUsageByDestinationData(APIUsageStatisticsClientConstants.API_USAGEBY_DESTINATION_SUMMARY,
                        fromDate, toDate);

        //get all the apis of the provider
        List<API> providerAPIs = getAPIsByProvider(providerName);
        List<APIDestinationUsageDTO> usageByResourcePath = new ArrayList<APIDestinationUsageDTO>();

        //iterate over all the result data
        for (APIUsageByDestination usage : usageData) {
            //iterate over all the apis of the provider
            for (API providerAPI : providerAPIs) {
                //consider the result if, api is part of the provider's api list
                if (providerAPI.getId().getApiName().equals(usage.getApiName()) &&
                        providerAPI.getId().getVersion().equals(usage.getApiVersion()) &&
                        providerAPI.getContext().equals(usage.getContext())) {

                    APIDestinationUsageDTO usageDTO = new APIDestinationUsageDTO();
                    usageDTO.setApiName(usage.getApiName());
                    usageDTO.setVersion(usage.getApiVersion());
                    usageDTO.setDestination(usage.getDestination());
                    usageDTO.setContext(usage.getContext());
                    usageDTO.setCount(usage.getRequestCount());
                    usageByResourcePath.add(usageDTO);
                }
            }
        }
        return usageByResourcePath;
    }

    /**
     * search and return the destination related data
     *
     * @param tableName name of the table in the database
     * @param fromDate  starting date of the results
     * @param toDate    ending date of the results
     * @return a collection containing the API usage data of destination
     * @throws APIMgtUsageQueryServiceClientException
     */
    private List<APIUsageByDestination> getAPIUsageByDestinationData(String tableName, String fromDate, String toDate)
            throws APIMgtUsageQueryServiceClientException {

        String query = null;

        //extending lucene query with time ranges
        try {
            query = "max_request_time: [" + RestClientUtil.getFloorDateAsLong(fromDate) + " TO " + RestClientUtil
                    .getCeilingDateAsLong(toDate) + "]";
        } catch (ParseException e) {
            handleException("Error occurred while Error parsing date", e);
        }

        //creating request bean
        SearchRequestBean request = new SearchRequestBean(query, 3, "api_version_context_dest_facet",
                "API_DESTINATION_SUMMARY");

        ArrayList<AggregateField> fields = new ArrayList<AggregateField>();
        AggregateField field = new AggregateField("total_request_count", "SUM", "totalRequestCount");
        fields.add(field);
        request.setAggregateFields(fields);

        //get the type of the required result type
        Type type = new TypeToken<List<Result<APIUsageByDestinationValue>>>() {
        }.getType();

        List<Result<APIUsageByDestinationValue>> obj = null;

        //do post and get the results
        try {
            obj = restClient.doPost(request, type);
        } catch (JsonSyntaxException e) {
            handleException("Error occurred while parsing response", e);
        } catch (IOException e) {
            handleException("Error occurred while Connecting to DAS REST API", e);
        }

        List<APIUsageByDestination> usageByResourcePath = new ArrayList<APIUsageByDestination>();

        //check the result status
        if (obj == null || obj.isEmpty()) {
            return usageByResourcePath;
        }

        //get the POJO class from the response bean classes
        //getColumnNames 0 index contain the api name, index 1 contain the version, 2 index contain userId, 3 index contain destination
        APIUsageByDestination usage;
        for (Result<APIUsageByDestinationValue> result : obj) {
            APIUsageByDestinationValue v = result.getValues();

            usage = new APIUsageByDestination();
            usage.setRequestCount(v.getTotalRequesCount());
            usage.setApiName(v.getColumnNames().get(0));
            usage.setApiVersion(v.getColumnNames().get(1));
            usage.setContext(v.getColumnNames().get(2));
            usage.setDestination(v.getColumnNames().get(3));

            usageByResourcePath.add(usage);
        }

        return usageByResourcePath;

    }

    /**
     * Returns a list of APIUsageDTO objects that contain information related to APIs that
     * belong to a particular provider and the number of total API calls each API has processed
     * up to now. This method does not distinguish between different API versions. That is all
     * versions of a single API are treated as one, and their individual request counts are summed
     * up to calculate a grand total per each API.
     *
     * @param providerName Name of the API provider
     * @param fromDate     starting date of the results
     * @param toDate       ending date of the results
     * @param limit        limit of the result
     * @return a List of APIUsageDTO objects - possibly empty
     * @throws APIMgtUsageQueryServiceClientException
     */
    @Override
    public List<APIUsageDTO> getProviderAPIUsage(String providerName, String fromDate, String toDate, int limit)
            throws APIMgtUsageQueryServiceClientException {
        //get the api usage data of the apis
        List<APIUsage> usageData = getUsageByAPIsData(APIUsageStatisticsClientConstants.API_VERSION_USAGE_SUMMARY,
                fromDate, toDate, limit);
        //get all the apis of the provider
        List<API> providerAPIs = getAPIsByProvider(providerName);
        Map<String, APIUsageDTO> usageByAPIs = new TreeMap<String, APIUsageDTO>();

        //iterate over all the result data
        for (APIUsage usage : usageData) {
            //iterate over all the apis of the provider
            for (API providerAPI : providerAPIs) {
                //consider the result if, api is part of the provider's api list
                if (providerAPI.getId().getApiName().equals(usage.getApiName()) &&
                        providerAPI.getId().getVersion().equals(usage.getApiVersion()) &&
                        providerAPI.getContext().equals(usage.getContext())) {

                    String[] apiData = { usage.getApiName(), usage.getApiVersion(),
                            providerAPI.getId().getProviderName() };

                    //format result using json array
                    JSONArray jsonArray = new JSONArray();
                    jsonArray.add(0, apiData[0]);
                    jsonArray.add(1, apiData[1]);
                    jsonArray.add(2, apiData[2]);
                    String apiName = jsonArray.toJSONString();

                    //get the api usages
                    APIUsageDTO usageDTO = usageByAPIs.get(apiName);
                    if (usageDTO != null) {
                        usageDTO.setCount(usageDTO.getCount() + usage.getRequestCount());
                    } else {
                        usageDTO = new APIUsageDTO();
                        usageDTO.setApiName(apiName);
                        usageDTO.setCount(usage.getRequestCount());
                        usageByAPIs.put(apiName, usageDTO);
                    }
                }
            }
        }
        List<APIUsageDTO> usage = getAPIUsageTopEntries(new ArrayList<APIUsageDTO>(usageByAPIs.values()), limit);
        return usage;
    }

    /**
     * This method gets the usage data for a given API across all versions
     *
     * @param tableName name of the required table
     * @param fromDate  starting date of the results
     * @param toDate    ending date of the results
     * @param limit     limit of the results
     * @return a collection containing the API usage data
     * @throws APIMgtUsageQueryServiceClientException if an error occurs while querying
     */
    private List<APIUsage> getUsageByAPIsData(String tableName, String fromDate, String toDate, int limit)
            throws APIMgtUsageQueryServiceClientException {

        //limit is used after DAS provide pagination of aggregate search
        String query = null;

        //extending lucene query with time ranges
        try {
            query = "max_request_time: [" + RestClientUtil.getFloorDateAsLong(fromDate) + " TO " + RestClientUtil
                    .getCeilingDateAsLong(toDate) + "]";
        } catch (ParseException e) {
            handleException("Error occurred while Error parsing date", e);
        }

        //creating request bean
        SearchRequestBean request = new SearchRequestBean(query, 2, "api_version_context_facet",
                "API_VERSION_USAGE_SUMMARY");

        ArrayList<AggregateField> fields = new ArrayList<AggregateField>();
        AggregateField field = new AggregateField("total_request_count", "SUM", "totalRequestCount");
        fields.add(field);
        request.setAggregateFields(fields);

        Type type = new TypeToken<List<Result<UsageByAPIsValue>>>() {
        }.getType();

        List<Result<UsageByAPIsValue>> obj = null;

        //do post and get the results
        try {
            obj = restClient.doPost(request, type);
        } catch (JsonSyntaxException e) {
            handleException("Error occurred while parsing response", e);
        } catch (IOException e) {
            handleException("Error occurred while Connecting to DAS REST API", e);
        }

        List<APIUsage> usageDataList = new ArrayList<APIUsage>();

        //check the result status
        if (obj == null || obj.isEmpty()) {
            return usageDataList;
        }

        //get the POJO class from the response bean classes
        //getColumnNames 0 index contain the api name, index 1 contain the version, 2 index contain context
        APIUsage usage;
        for (Result<UsageByAPIsValue> result : obj) {
            UsageByAPIsValue v = result.getValues();

            usage = new APIUsage();
            usage.setRequestCount(v.getTotalRequestCount());
            usage.setApiName(v.getColumnNames().get(0));
            usage.setApiVersion(v.getColumnNames().get(1));
            usage.setContext(v.getColumnNames().get(2));

            usageDataList.add(usage);
        }

        return usageDataList;
    }

    /**
     * Gets a list of APIResponseFaultCountDTO objects containing information related to APIs belonging
     * to a particular provider
     *
     * @param providerName Name of the API provider
     * @param fromDate     starting date of the results
     * @param toDate       ending date of the results
     * @return a List of APIResponseFaultCountDTO objects, possibly empty
     * @throws APIMgtUsageQueryServiceClientException
     */
    @Override
    public List<APIResponseFaultCountDTO> getAPIResponseFaultCount(String providerName, String fromDate, String toDate)
            throws APIMgtUsageQueryServiceClientException {

        //get the response fault count data of the apis
        List<APIResponseFaultCount> faultyData = this
                .getAPIResponseFaultCountData(APIUsageStatisticsClientConstants.API_FAULT_SUMMARY, fromDate, toDate);

        //get all the apis of the provider
        List<API> providerAPIs = getAPIsByProvider(providerName);

        List<APIResponseFaultCountDTO> faultyCount = new ArrayList<APIResponseFaultCountDTO>();
        List<APIVersionUsageDTO> apiVersionUsageList;
        APIVersionUsageDTO apiVersionUsageDTO;

        //iterate over all the result data
        for (APIResponseFaultCount fault : faultyData) {
            //iterate over all the apis of the provider
            for (API providerAPI : providerAPIs) {
                //consider the result if, api is part of the provider's api list
                if (providerAPI.getId().getApiName().equals(fault.getApiName()) &&
                        providerAPI.getId().getVersion().equals(fault.getApiVersion()) &&
                        providerAPI.getContext().equals(fault.getContext())) {

                    APIResponseFaultCountDTO faultyDTO = new APIResponseFaultCountDTO();
                    faultyDTO.setApiName(fault.getApiName());
                    faultyDTO.setVersion(fault.getApiVersion());
                    faultyDTO.setContext(fault.getContext());
                    faultyDTO.setCount(fault.getFaultCount());

                    //get the usage of the apis individually
                    apiVersionUsageList = getUsageByAPIVersions(providerName, fault.getApiName(), fromDate, toDate);
                    for (int i = 0; i < apiVersionUsageList.size(); i++) {
                        apiVersionUsageDTO = apiVersionUsageList.get(i);
                        //if both version are equal
                        if (apiVersionUsageDTO.getVersion().equals(fault.getApiVersion())) {
                            //get all the request count
                            long requestCount = apiVersionUsageDTO.getCount();

                            //get the fault percentage
                            double faultPercentage = ((double) fault.getFaultCount()) / requestCount * 100;
                            DecimalFormat twoDForm = new DecimalFormat("#.##");

                            //format fault percentage
                            faultPercentage = Double.valueOf(twoDForm.format(faultPercentage));
                            faultyDTO.setFaultPercentage(faultPercentage);
                            faultyDTO.setTotalRequestCount(requestCount);
                            break;
                        }
                    }

                    //commented old code for re-validating new cde
                    /*for (int i = 0; i < apiVersionUsageList.size(); i++) {
                        apiVersionUsageDTO = apiVersionUsageList.get(i);
                        if (apiVersionUsageDTO.getVersion().equals(fault.apiVersion)) {
                            long requestCount = apiVersionUsageDTO.getCount();
                            double faultPercentage = ((double)requestCount - fault.faultCount) / requestCount * 100;
                            DecimalFormat twoDForm = new DecimalFormat("#.##");
                            faultPercentage = 100 - Double.valueOf(twoDForm.format(faultPercentage));
                            faultyDTO.setFaultPercentage(faultPercentage);
                            faultyDTO.setTotalRequestCount(requestCount);
                            break;
                        }
                    }*/

                    faultyCount.add(faultyDTO);

                }
            }
        }
        return faultyCount;
    }

    /**
     * This method gets the fault usage data
     *
     * @param tableName name of the required table
     * @param fromDate  starting date of the results
     * @param toDate    ending date of the results
     * @return a List of APIResponseFaultCount objects, possibly empty
     * @throws APIMgtUsageQueryServiceClientException
     */
    private List<APIResponseFaultCount> getAPIResponseFaultCountData(String tableName, String fromDate, String toDate)
            throws APIMgtUsageQueryServiceClientException {
        //limit is used after DAS provide pagination of aggregate search
        String query = null;

        //extending lucene query with time ranges
        try {
            query = "max_request_time: [" + RestClientUtil.getFloorDateAsLong(fromDate) + " TO " + RestClientUtil
                    .getCeilingDateAsLong(toDate) + "]";
        } catch (ParseException e) {
            handleException("Error occurred while Error parsing date", e);
        }

        //creating request bean
        SearchRequestBean request = new SearchRequestBean(query, 3, "api_version_apiPublisher_context_facet",
                "API_FAULT_SUMMARY");

        ArrayList<AggregateField> fields = new ArrayList<AggregateField>();
        AggregateField field = new AggregateField("total_fault_count", "SUM", "totalFaultCount");
        fields.add(field);
        request.setAggregateFields(fields);

        //get the type of the required result type
        Type type = new TypeToken<List<Result<APIResponseFaultCountValue>>>() {
        }.getType();

        List<Result<APIResponseFaultCountValue>> obj = null;

        //do post and get the results
        try {
            obj = restClient.doPost(request, type);
        } catch (JsonSyntaxException e) {
            handleException("Error occurred while parsing response", e);
        } catch (IOException e) {
            handleException("Error occurred while Connecting to DAS REST API", e);
        }

        List<APIResponseFaultCount> faultUsage = new ArrayList<APIResponseFaultCount>();

        //check the result status
        if (obj == null || obj.isEmpty()) {
            return faultUsage;
        }

        //get the POJO class from the response bean classes
        //getColumnNames 0 index contain the api name, index 1 contain the version, 2 index contain publisher, 3 index contain context
        APIResponseFaultCount usage;
        for (Result<APIResponseFaultCountValue> result : obj) {
            APIResponseFaultCountValue v = result.getValues();

            usage = new APIResponseFaultCount();
            usage.setFaultCount(v.getTotalFaultCount());
            usage.setApiName(v.getColumnNames().get(0));
            usage.setApiVersion(v.getColumnNames().get(1));
            usage.setContext(v.getColumnNames().get(3));

            faultUsage.add(usage);
        }

        return faultUsage;

    }

    //Throttling related Methods

    /**
     * Given API name and Application, returns throttling request counts over time for a given time span
     *
     * @param apiName  Name of the API
     * @param provider Provider name
     * @param appName  Application name
     * @param fromDate Start date of the time span
     * @param toDate   End date of time span
     * @param groupBy  Group by parameter. Supported parameters are :day,hour
     * @return Throttling counts over time
     * @throws APIMgtUsageQueryServiceClientException
     */
    @Override
    public List<APIThrottlingOverTimeDTO> getThrottleDataOfAPIAndApplication(String apiName, String provider,
            String appName, String fromDate, String toDate, String groupBy)
            throws APIMgtUsageQueryServiceClientException {

        //get the tenant domain
        String tenantDomain = MultitenantUtils.getTenantDomain(provider);
        //set the query to match tenant
        String query = "tenantDomain:" + tenantDomain;

        //if application or api is no available return empty result
        if (apiName.contains("No APIs Available")) {
            return new ArrayList<APIThrottlingOverTimeDTO>();
        }

        //if provider is not ALL_PROVIDERS set the query to preserve specific provider
        if (!provider.startsWith(APIUsageStatisticsClientConstants.ALL_PROVIDERS)) {
            query += " AND apiPublisher:" + provider;
        }

        //set the application name
        if (!StringUtils.isEmpty(appName)) {
            query += " AND " + "applicationName:" + appName;
        }

        //lucene query with time ranges
        try {
            query += " AND api:" + apiName + " AND " + "max_request_time: [" + RestClientUtil
                    .getFloorDateAsLong(fromDate) + " TO " + RestClientUtil.getCeilingDateAsLong(toDate) + "]";
        } catch (ParseException e) {
            handleException("Error occurred while Error parsing date", e);
        }

        //creating request bean
        SearchRequestBean request = new SearchRequestBean(query, 2, "api_apiPublisher_applicationName_facet",
                "API_THROTTLED_OUT_SUMMARY");

        ArrayList<AggregateField> fields = new ArrayList<AggregateField>();

        //set the aggregate request to get success count
        AggregateField success_request_count_fields = new AggregateField("success_request_count", "SUM",
                "success_request_count");
        fields.add(success_request_count_fields);

        //set the aggregate request to get max time
        AggregateField max_request_time_fields = new AggregateField("max_request_time", "MAX", "max_request_time");
        fields.add(max_request_time_fields);

        //set the aggregate request to get success throttle count
        AggregateField throttle_out_count_fields = new AggregateField("throttleout_count", "SUM", "throttle_out_count");
        fields.add(throttle_out_count_fields);
        request.setAggregateFields(fields);

        //get the type of the required result type
        Type type = new TypeToken<List<Result<APIsForThrottleStatsValue>>>() {
        }.getType();

        //do post and get the results
        List<Result<APIsForThrottleStatsValue>> obj = null;
        try {
            obj = restClient.doPost(request, type);
        } catch (JsonSyntaxException e) {
            handleException("Error occurred while parsing response", e);
        } catch (IOException e) {
            handleException("Error occurred while Connecting to DAS REST API", e);
        }

        //get the DTO class from the response bean classes
        //getColumnNames 0 index contain the api name, index 1 contain the publisher, 2 index contain application
        List<APIThrottlingOverTimeDTO> throttlingData = new ArrayList<APIThrottlingOverTimeDTO>();
        APIThrottlingOverTimeDTO usage;
        for (Result<APIsForThrottleStatsValue> result : obj) {
            APIsForThrottleStatsValue v = result.getValues();

            String api = v.getColumnNames().get(0);
            String publisher = v.getColumnNames().get(1);
            String time = RestClientUtil.longToDate(v.getMax_request_time());
            usage = new APIThrottlingOverTimeDTO(api, publisher, v.getSuccess_request_count(),
                    v.getThrottle_out_count(), time);
            throttlingData.add(usage);
        }
        return throttlingData;
    }

    /**
     * Given Application name and the provider, returns throttle data for the APIs of the provider invoked by the
     * given application
     *
     * @param appName  Application name
     * @param provider Provider name
     * @param fromDate Start date of the time span
     * @param toDate   End date of time span
     * @return Throttling counts of APIs of the provider invoked by the given app
     * @throws APIMgtUsageQueryServiceClientException
     */
    @Override
    public List<APIThrottlingOverTimeDTO> getThrottleDataOfApplication(String appName, String provider, String fromDate,
            String toDate) throws APIMgtUsageQueryServiceClientException {

        //get the tenant domain
        String tenantDomain = MultitenantUtils.getTenantDomain(provider);
        //set the query to match tenant
        String query = "tenantDomain:" + tenantDomain;

        //if application or api is no available return empty result
        if (appName.contains("No Apps Available")) {
            return new ArrayList<APIThrottlingOverTimeDTO>();
        }

        //if provider is not ALL_PROVIDERS set the query to preserve specific provider
        if (!provider.startsWith(APIUsageStatisticsClientConstants.ALL_PROVIDERS)) {
            query += " AND apiPublisher:" + provider;
        }

        //lucene query with time ranges
        try {
            query += " AND applicationName:" + appName + " AND " + "max_request_time: [" + RestClientUtil
                    .getFloorDateAsLong(fromDate) + " TO " + RestClientUtil.getCeilingDateAsLong(toDate) + "]";
        } catch (ParseException e) {
            handleException("Error occurred while Error parsing date", e);
        }

        //creating request bean
        SearchRequestBean request = new SearchRequestBean(query, 1, "api_apiPublisher_applicationName_facet",
                "API_THROTTLED_OUT_SUMMARY");

        //set the aggregate request to get success count
        ArrayList<AggregateField> fields = new ArrayList<AggregateField>();
        AggregateField success_request_count_field = new AggregateField("success_request_count", "SUM",
                "success_request_count");
        fields.add(success_request_count_field);

        //set the aggregate request to get max time
        AggregateField max_request_time_fields = new AggregateField("max_request_time", "MAX", "max_request_time");
        fields.add(max_request_time_fields);

        //set the aggregate request to get throttle count
        AggregateField throttle_out_count_fields = new AggregateField("throttleout_count", "SUM", "throttle_out_count");
        fields.add(throttle_out_count_fields);

        request.setAggregateFields(fields);

        //get the type of the required result type
        Type type = new TypeToken<List<Result<APIsForThrottleStatsValue>>>() {
        }.getType();

        //do post and get the results
        List<Result<APIsForThrottleStatsValue>> obj = null;
        try {
            obj = restClient.doPost(request, type);
        } catch (JsonSyntaxException e) {
            handleException("Error occurred while parsing response", e);
        } catch (IOException e) {
            handleException("Error occurred while Connecting to DAS REST API", e);
        }

        List<APIThrottlingOverTimeDTO> throttlingAppData = new ArrayList<APIThrottlingOverTimeDTO>();

        //get the DTO class from the response bean classes
        //getColumnNames 0 index contain the api name, index 1 contain the publisher, 2 index contain application
        APIThrottlingOverTimeDTO usage;
        for (Result<APIsForThrottleStatsValue> result : obj) {
            APIsForThrottleStatsValue v = result.getValues();
            String api = v.getColumnNames().get(0);
            String publisher = v.getColumnNames().get(1);
            String time = RestClientUtil.longToDate(v.getMax_request_time());
            usage = new APIThrottlingOverTimeDTO(api, publisher, v.getSuccess_request_count(),
                    v.getThrottle_out_count(), time);
            throttlingAppData.add(usage);
        }
        return throttlingAppData;
    }

    /**
     * Get APIs of the provider that consist of throttle data
     *
     * @param provider Provider name
     * @return List of APIs of the provider that consist of throttle data
     * @throws APIMgtUsageQueryServiceClientException
     */
    @Override
    public List<String> getAPIsForThrottleStats(String provider) throws APIMgtUsageQueryServiceClientException {

        //get the tenant domain
        String tenantDomain = MultitenantUtils.getTenantDomain(provider);
        //set the query to match tenant
        String query = "tenantDomain:" + tenantDomain;

        //if provider is not ALL_PROVIDERS set the query to preserve specific provider
        if (!provider.startsWith(APIUsageStatisticsClientConstants.ALL_PROVIDERS)) {
            query += " AND apiPublisher:" + provider;
        }

        //creating request bean
        SearchRequestBean request = new SearchRequestBean(query, 0, "api_apiPublisher_applicationName_facet",
                "API_THROTTLED_OUT_SUMMARY");

        ArrayList<AggregateField> fields = new ArrayList<AggregateField>();
        AggregateField field = new AggregateField("success_request_count", "SUM", "success_request_count");
        fields.add(field);
        request.setAggregateFields(fields);

        //get the type of the required result type
        Type type = new TypeToken<List<Result<APIsForThrottleStatsValue>>>() {
        }.getType();
        List<Result<APIsForThrottleStatsValue>> obj = null;

        //do post and get the results
        try {
            obj = restClient.doPost(request, type);
        } catch (JsonSyntaxException e) {
            handleException("Error occurred while parsing response", e);
        } catch (IOException e) {
            handleException("Error occurred while Connecting to DAS REST API", e);
        }

        //crete new list with apis in result
        List<String> throttlingAPIData = new ArrayList<String>();
        for (Result<APIsForThrottleStatsValue> result : obj) {
            APIsForThrottleStatsValue v = result.getValues();
            //getColumnNames 1 st element is api name
            throttlingAPIData.add(v.getColumnNames().get(0));
        }
        return throttlingAPIData;
    }

    /**
     * Given provider name and the API name, returns a list of applications through which the corresponding API is
     * invoked and which consist of success/throttled requests
     *
     * @param provider Provider name
     * @param apiName  Name of th API
     * @return A list of applications through which the corresponding API is invoked and which consist of throttle data
     * @throws APIMgtUsageQueryServiceClientException
     */
    @Override
    public List<String> getAppsForThrottleStats(String provider, String apiName)
            throws APIMgtUsageQueryServiceClientException {
        //get the tenant domain

        String tenantDomain = MultitenantUtils.getTenantDomain(provider);
        //set the query to match tenant
        String query = "tenantDomain:" + tenantDomain;

        //if provider is not ALL_PROVIDERS set the query to preserve specific provider
        if (!provider.startsWith(APIUsageStatisticsClientConstants.ALL_PROVIDERS)) {
            query += " AND apiPublisher:" + provider;
        }

        //set the query to find specific api
        if (apiName != null) {
            if (apiName.contains("No APIs Available")) {
                return new ArrayList<String>();
            }
            query += " AND api:" + apiName;
        }

        //creating request bean
        SearchRequestBean request = new SearchRequestBean(query, 0, "applicationName_facet",
                "API_THROTTLED_OUT_SUMMARY");

        ArrayList<AggregateField> fields = new ArrayList<AggregateField>();
        AggregateField field = new AggregateField("success_request_count", "SUM", "success_request_count");
        fields.add(field);
        request.setAggregateFields(fields);

        //get the type of the required result type
        Type type = new TypeToken<List<Result<APPsForThrottleStatsValue>>>() {
        }.getType();
        List<Result<APPsForThrottleStatsValue>> obj = null;

        //do post and get the results
        try {
            obj = restClient.doPost(request, type);
        } catch (JsonSyntaxException e) {
            handleException("Error occurred while parsing response", e);
        } catch (IOException e) {
            handleException("Error occurred while Connecting to DAS REST API", e);
        }

        //crete new list with apps in result
        List<String> throttlingAppData = new ArrayList<String>();
        for (Result<APPsForThrottleStatsValue> result : obj) {
            APPsForThrottleStatsValue v = result.getValues();
            //getColumnNames 1 st element is app name
            throttlingAppData.add(v.getColumnNames().get(0));
        }
        return throttlingAppData;
    }

    /**
     * Returns a list of APIVersionUsageDTO objects that contain information related to a
     * particular API of a specified provider, along with the number of API calls processed
     * by each version of that API for a particular time preriod.
     *
     * @param providerName Name of the API provider
     * @param apiName      name of the API
     * @param fromDate     starting date of the results
     * @param toDate       ending date of the results
     * @return a list containing the data related to API usage
     * @throws org.wso2.carbon.apimgt.usage.client.exception.APIMgtUsageQueryServiceClientException
     */
    @Override
    public List<APIVersionUsageDTO> getUsageByAPIVersions(String providerName, String apiName, String fromDate,
            String toDate) throws APIMgtUsageQueryServiceClientException {

        //get the api usgae data of the apis by version
        List<APIUsage> usageData = this
                .getUsageByAPIVersionsData(APIUsageStatisticsClientConstants.API_VERSION_USAGE_SUMMARY, fromDate,
                        toDate, apiName);

        //get all the apis of the provider
        List<API> providerAPIs = getAPIsByProvider(providerName);
        Map<String, APIVersionUsageDTO> usageByVersions = new TreeMap<String, APIVersionUsageDTO>();

        //iterate over all the result data
        for (APIUsage usage : usageData) {
            //iterate over all the apis of the provider
            for (API providerAPI : providerAPIs) {
                //consider the result if, api is part of the provider's api list
                if (providerAPI.getId().getApiName().equals(usage.getApiName()) &&
                        providerAPI.getId().getVersion().equals(usage.getApiVersion()) &&
                        providerAPI.getContext().equals(usage.getContext())) {

                    APIVersionUsageDTO usageDTO = new APIVersionUsageDTO();
                    usageDTO.setVersion(usage.getApiVersion());
                    usageDTO.setCount(usage.getRequestCount());
                    usageByVersions.put(usage.getApiVersion(), usageDTO);
                }
            }
        }
        return new ArrayList<APIVersionUsageDTO>(usageByVersions.values());
    }

    /**
     * Query the REST API and return the API usage data
     *
     * @param tableName name of the required table
     * @param fromDate  starting date of the results
     * @param toDate    ending date of the results
     * @param apiName   name of the API
     * @return a list containing the data related to API usage
     * @throws APIMgtUsageQueryServiceClientException
     */
    private List<APIUsage> getUsageByAPIVersionsData(String tableName, String fromDate, String toDate, String apiName)
            throws APIMgtUsageQueryServiceClientException {

        String query = null;

        //lucene query with time ranges
        try {
            query = "api:" + apiName + " AND max_request_time: [" + RestClientUtil.getFloorDateAsLong(fromDate) + " TO "
                    + RestClientUtil.getCeilingDateAsLong(toDate) + "]";
        } catch (ParseException e) {
            handleException("Error occurred while Error parsing date", e);
        }

        //creating request bean
        SearchRequestBean request = new SearchRequestBean(query, 2, "api_version_context_facet",
                "API_VERSION_USAGE_SUMMARY");

        ArrayList<AggregateField> fields = new ArrayList<AggregateField>();
        AggregateField field = new AggregateField("total_request_count", "SUM", "totalRequestCount");
        fields.add(field);
        request.setAggregateFields(fields);

        //get the type of the required result type
        Type type = new TypeToken<List<Result<UsageByAPIVersionsValue>>>() {
        }.getType();

        List<Result<UsageByAPIVersionsValue>> obj = null;

        //do post and get the results
        try {
            obj = restClient.doPost(request, type);
        } catch (JsonSyntaxException e) {
            handleException("Error occurred while parsing response", e);
        } catch (IOException e) {
            handleException("Error occurred while Connecting to DAS REST API", e);
        }

        List<APIUsage> usageDataList = new ArrayList<APIUsage>();

        //check the result status
        if (obj == null || obj.isEmpty()) {
            return usageDataList;
        }

        //get the POJO class from the response bean classes
        //getColumnNames 0 index contain the api name, index 1 contain the version, 2 index contain context
        APIUsage usage;
        for (Result<UsageByAPIVersionsValue> result : obj) {
            UsageByAPIVersionsValue v = result.getValues();

            usage = new APIUsage();
            usage.setRequestCount(v.getTotalRequestCount());
            usage.setApiName(v.getColumnNames().get(0));
            usage.setApiVersion(v.getColumnNames().get(1));
            usage.setContext(v.getColumnNames().get(2));

            usageDataList.add(usage);
        }

        return usageDataList;

    }

    /**
     * Return the First access date according to the REST API table data
     *
     * @param providerName provider name
     * @return APIFirstAccess containing date
     * @throws APIMgtUsageQueryServiceClientException
     */
    @Override
    public List<APIFirstAccess> getFirstAccessTime(String providerName) throws APIMgtUsageQueryServiceClientException {

        //check whether API_UTIL class id present
        if (!isTableExist("API_UTIL")) {
            return new ArrayList<APIFirstAccess>();
        }

        //getting the search bean
        APIFirstAccess firstAccess = this.queryFirstAccess("API_UTIL");
        List<APIFirstAccess> APIFirstAccessList = new ArrayList<APIFirstAccess>();

        APIFirstAccess fTime;

        //if result are not null create the result and return
        if (firstAccess != null) {
            fTime = new APIFirstAccess(firstAccess.getYear(), firstAccess.getMonth(), firstAccess.getDay());
            APIFirstAccessList.add(fTime);
        }
        return APIFirstAccessList;
    }

    /**
     * Query the REST api and get First access time data
     *
     * @param tableName name of the table
     * @return pojo class containg the response result
     * @throws APIMgtUsageQueryServiceClientException
     */
    private APIFirstAccess queryFirstAccess(String tableName) throws APIMgtUsageQueryServiceClientException {

        //create the bean
        FirstAccessRequestSearchBean request = new FirstAccessRequestSearchBean("", 0, 1, tableName);

        //get the type for expected result
        Type type = new TypeToken<List<Result<FirstAccessValue>>>() {
        }.getType();

        List<Result<FirstAccessValue>> obj = null;

        //do post and get the results
        try {
            obj = restClient.doPost(request, type);
        } catch (JsonSyntaxException e) {
            handleException("Error occurred while parsing response", e);
        } catch (IOException e) {
            handleException("Error occurred while Connecting to DAS REST API", e);
        }

        //get the long value from the result
        APIFirstAccess firstAccess = null;
        long accessTime = obj.get(0).getValues().getFirst_access_time();

        //check whether time is valid
        if (accessTime > 0) {
            //set the time to calendar
            Calendar cc = Calendar.getInstance();
            cc.setTimeInMillis(accessTime);

            //get the date as string
            String year = cc.get(Calendar.YEAR) + "";
            String month = cc.get(Calendar.MONTH) + "";
            String day = cc.get(Calendar.DATE) + "";

            firstAccess = new APIFirstAccess(year, month, day);
        }
        return firstAccess;

    }

    /**
     * return status of the Table in DAS REST API
     *
     * @param tableName table name to serach
     * @return boolean indicating table present
     * @throws APIMgtUsageQueryServiceClientException
     */
    private boolean isTableExist(String tableName) throws APIMgtUsageQueryServiceClientException {

        TableExistResponseBean status;
        //invoke restClient isTableExist function
        try {
            status = restClient.isTableExist(tableName);
        } catch (JsonSyntaxException e) {
            log.error("Error occurred while parsing response", e);
            throw new APIMgtUsageQueryServiceClientException("Error occurred while parsing response", e);
        } catch (IOException e) {
            log.error("Error occurred while Connecting to DAS REST API");
            throw new APIMgtUsageQueryServiceClientException("Error occurred while Connecting to DAS REST API", e);
        }

        //status contain the "success" if table present
        boolean isExist = status.getStatus().equalsIgnoreCase("success");
        return isExist;

    }

    //not used due to waiting for DAS REST pagination support
    private List<PerUserAPIUsageDTO> getTopEntries(List<PerUserAPIUsageDTO> usageData, int limit) {
        Collections.sort(usageData, new Comparator<PerUserAPIUsageDTO>() {
            public int compare(PerUserAPIUsageDTO o1, PerUserAPIUsageDTO o2) {
                // Note that o2 appears before o1
                // This is because we need to sort in the descending order
                return (int) (o2.getCount() - o1.getCount());
            }
        });
        if (usageData.size() > limit) {
            PerUserAPIUsageDTO other = new PerUserAPIUsageDTO();
            other.setUsername("[Other]");
            for (int i = limit; i < usageData.size(); i++) {
                other.setCount(other.getCount() + usageData.get(i).getCount());
            }
            while (usageData.size() > limit) {
                usageData.remove(limit);
            }
            usageData.add(other);
        }

        return usageData;
    }

    //not used due to waiting for DAS REST pagination support
    private List<APIUsageDTO> getAPIUsageTopEntries(List<APIUsageDTO> usageData, int limit) {
        Collections.sort(usageData, new Comparator<APIUsageDTO>() {
            public int compare(APIUsageDTO o1, APIUsageDTO o2) {
                // Note that o2 appears before o1
                // This is because we need to sort in the descending order
                return (int) (o2.getCount() - o1.getCount());
            }
        });
        if (usageData.size() > limit) {
            APIUsageDTO other = new APIUsageDTO();
            other.setApiName("[\"Other\"]");
            for (int i = limit; i < usageData.size(); i++) {
                other.setCount(other.getCount() + usageData.get(i).getCount());
            }
            while (usageData.size() > limit) {
                usageData.remove(limit);
            }
            usageData.add(other);
        }

        return usageData;
    }

    /**
     * get the list of API of the Provider
     *
     * @param providerId name of the provider
     * @return list of apis
     * @throws APIMgtUsageQueryServiceClientException
     */
    private List<API> getAPIsByProvider(String providerId) throws APIMgtUsageQueryServiceClientException {
        try {
            if (APIUsageStatisticsClientConstants.ALL_PROVIDERS.equals(providerId)) {
                return apiProviderImpl.getAllAPIs();
            } else {
                return apiProviderImpl.getAPIsByProvider(providerId);
            }
        } catch (APIManagementException e) {
            throw new APIMgtUsageQueryServiceClientException("Error while retrieving APIs by " + providerId, e);
        }
    }

    //REST Client no need to use this
    @Override
    public List<APIUsageRangeCost> evaluate(String param, int calls) throws Exception {
        return paymentPlan.evaluate(param, calls);
    }

    /**
     * Custom artifacts deployment. deploy capp related to REST API client on DAS
     *
     * @param url  url of the DAS
     * @param user user name
     * @param pass password
     * @throws Exception general exception throws, because different exception can occur
     */
    @Override
    public void deployArtifacts(String url, String user, String pass) throws Exception {

        //name of the capp to deploy
        String cAppName = "API_Manager_Analytics.car";
        String cAppPath = System.getProperty("carbon.home") + "/statistics";
        cAppPath = cAppPath + '/' + cAppName;
        File file = new File(cAppPath);

        //get the byte array of file
        byte[] byteArray = FileUtils.readFileToByteArray(file);
        DataHandler dataHandler = new DataHandler(byteArray, "application/octet-stream");

        //create the stub to deploy artifacts
        CarbonAppUploaderStub stub = new CarbonAppUploaderStub(url + "/services/CarbonAppUploader");
        ServiceClient client = stub._getServiceClient();
        Options options = client.getOptions();
        //set the security
        HttpTransportProperties.Authenticator authenticator = new HttpTransportProperties.Authenticator();
        authenticator.setUsername(user);
        authenticator.setPassword(pass);
        authenticator.setPreemptiveAuthentication(true);
        options.setProperty(org.apache.axis2.transport.http.HTTPConstants.AUTHENTICATE, authenticator);
        client.setOptions(options);
        log.info("Deploying DAS cApp '" + cAppName + "'...");

        //create UploadedFileItem array and 1st element contain the deploy artifact
        UploadedFileItem[] fileItem = new UploadedFileItem[1];
        fileItem[0] = new UploadedFileItem();
        fileItem[0].setDataHandler(dataHandler);
        fileItem[0].setFileName(cAppName);
        fileItem[0].setFileType("jar");

        //upload the artifacts
        stub.uploadApp(fileItem);
    }

    /**
     * Use to handle exception of common type in single step
     *
     * @param msg custom message
     * @param e   throwable object of the exception
     * @throws APIMgtUsageQueryServiceClientException
     */
    private static void handleException(String msg, Throwable e) throws APIMgtUsageQueryServiceClientException {
        log.error(msg, e);
        throw new APIMgtUsageQueryServiceClientException(msg, e);
    }

    /**
     * Get the Subscriber count and information related to the APIs
     *
     * @param loggedUser
     * @return list of SubscriberCountByAPIs
     * @throws APIManagementException
     */
    @Override
    public List<SubscriberCountByAPIs> getSubscriberCountByAPIs(String loggedUser) throws APIManagementException {
        return super.getSubscriberCountByAPIs(loggedUser, apiProviderImpl);
    }
}