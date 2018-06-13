<#list artifactId.components as parent>
  <a href="/${parent.fullPath}"><#if parent.simpleName?has_content>${parent.simpleName}<#else >/</#if></a>
</#list>