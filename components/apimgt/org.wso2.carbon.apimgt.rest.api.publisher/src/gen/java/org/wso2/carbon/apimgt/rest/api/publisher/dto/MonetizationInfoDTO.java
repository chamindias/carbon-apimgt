package org.wso2.carbon.apimgt.rest.api.publisher.dto;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.swagger.annotations.*;
import com.fasterxml.jackson.annotation.*;

import javax.validation.constraints.NotNull;





@ApiModel(description = "")
public class MonetizationInfoDTO  {
  
  
  @NotNull
  private Boolean enabled = null;
  
  
  private Map<String, String> properties = new HashMap<String, String>();

  
  /**
   * Flag to indicate the monetization status
   **/
  @ApiModelProperty(required = true, value = "Flag to indicate the monetization status")
  @JsonProperty("enabled")
  public Boolean getEnabled() {
    return enabled;
  }
  public void setEnabled(Boolean enabled) {
    this.enabled = enabled;
  }

  
  /**
   * Map of custom properties related to monetization
   **/
  @ApiModelProperty(value = "Map of custom properties related to monetization")
  @JsonProperty("properties")
  public Map<String, String> getProperties() {
    return properties;
  }
  public void setProperties(Map<String, String> properties) {
    this.properties = properties;
  }

  

  @Override
  public String toString()  {
    StringBuilder sb = new StringBuilder();
    sb.append("class MonetizationInfoDTO {\n");
    
    sb.append("  enabled: ").append(enabled).append("\n");
    sb.append("  properties: ").append(properties).append("\n");
    sb.append("}\n");
    return sb.toString();
  }
}
