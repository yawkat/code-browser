<#-- @ftlvariable name="artifactMetadata" type="at.yawk.javabrowser.ArtifactMetadata" -->
<div class="metadata">
  <#if artifactMetadata.url??><a rel="nofollow" href="${artifactMetadata.url}"></#if>
  <#if artifactMetadata.logoUrl??>
    <img src="<@imageCache url=artifactMetadata.logoUrl maxHeight=40 />" height="40px">
  </#if>

  <#if artifactMetadata.url??>
    <span>${artifactMetadata.url}</span></a><span><#if artifactMetadata.description??>: ${artifactMetadata.description}</#if></span>
  <#elseif artifactMetadata.description??>
    <span>${artifactMetadata.description}</span>
  </#if>
  <#if artifactMetadata.organization?? && (artifactMetadata.organization.url?? || artifactMetadata.organization.name??)>
    (<#if artifactMetadata.organization.url??><a rel="nofollow" href="${artifactMetadata.organization.url}"></#if>${(artifactMetadata.organization.name)!artifactMetadata.organization.url}<#if artifactMetadata.organization.url??></a></#if>)
  </#if>

  <#if artifactMetadata.licenses??>
    <div class="itemize" title="License">
      <i class="ij ij-documentation"></i>
      <ul>
        <#list artifactMetadata.licenses as license>
          <li>
            <#if license.url??><a rel="nofollow" href="${license.url}"></#if>
              ${license.name}
              <#if license.url??></a></#if>
          </li>
        </#list>
      </ul>
    </div>
  </#if>
  <#if artifactMetadata.contributors??>
    <div class="itemize" title="Contributors">
      <i class="ij ij-user"></i>
      <ul>
        <#list artifactMetadata.contributors as dev>
          <li>
            <#if dev.url??><a rel="nofollow" href="${dev.url}"></#if>
              ${dev.name}
              <#if dev.url??></a></#if>
            <#if dev.organization?? && (dev.organization.url?? || dev.organization.name??)>
              (<#if dev.organization.url??><a rel="nofollow" href="${dev.organization.url}"></#if>${(dev.organization.name)!dev.organization.url}<#if dev.organization.url??></a></#if>)
            </#if>
          </li>
        </#list>
      </ul>
    </div>
  </#if>
  <#if artifactMetadata.developers?? && artifactMetadata.developers[0].name??>
    <div class="itemize" title="Developers">
      <i class="ij ij-user"></i>
      <ul>
        <#list artifactMetadata.developers as dev>
          <li>
            <#if dev.url??><a rel="nofollow" href="${dev.url}"></#if>
              ${dev.name}
              <#if dev.url??></a></#if>
            <#if dev.organization?? && (dev.organization.url?? || dev.organization.name??)>
              (<#if dev.organization.url??><a rel="nofollow" href="${dev.organization.url}"></#if>${(dev.organization.name)!dev.organization.url}<#if dev.organization.url??></a></#if>)
            </#if>
          </li>
        </#list>
      </ul>
    </div>
  </#if>
</div>