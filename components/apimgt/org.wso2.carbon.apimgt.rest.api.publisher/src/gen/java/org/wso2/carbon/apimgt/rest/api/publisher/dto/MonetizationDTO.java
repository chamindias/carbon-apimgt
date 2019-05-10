package org.wso2.carbon.apimgt.rest.api.publisher.dto;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.swagger.annotations.*;
import com.fasterxml.jackson.annotation.*;

import javax.validation.constraints.NotNull;





@ApiModel(description = "")
public class MonetizationDTO  {
  
  
  @NotNull
  private Boolean status = null;
  
  
  private Map<String, String> attributes = new HashMap<String, String>();

  
  /**
   * This attribute declares whether monetization is enabled of not.\n
   **/
  @ApiModelProperty(required = true, value = "This attribute declares whether monetization is enabled of not.\n")
  @JsonProperty("status")
  public Boolean getStatus() {
    return status;
  }
  public void setStatus(Boolean status) {
    this.status = status;
  }

  
  /**
   * Custom attributes needed to complete the monetization task.\n
   **/
  @ApiModelProperty(value = "Custom attributes needed to complete the monetization task.\n")
  @JsonProperty("attributes")
  public Map<String, String> getAttributes() {
    return attributes;
  }
  public void setAttributes(Map<String, String> attributes) {
    this.attributes = attributes;
  }

  

  @Override
  public String toString()  {
    StringBuilder sb = new StringBuilder();
    sb.append("class MonetizationDTO {\n");
    
    sb.append("  status: ").append(status).append("\n");
    sb.append("  attributes: ").append(attributes).append("\n");
    sb.append("}\n");
    return sb.toString();
  }
}
