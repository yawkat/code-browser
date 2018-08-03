<a href="/">/</a>
<#assign fullPath></#assign>
<#list artifactId.nodes as node>
  <#if node.value?has_content>
  <#assign fullPath>${fullPath}/${node.value}</#assign>
  <a href="${fullPath}">${node.value}/</a>
  </#if>
</#list>