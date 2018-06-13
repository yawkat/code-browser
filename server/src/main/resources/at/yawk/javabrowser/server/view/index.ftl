<#-- @ftlvariable name="" type="at.yawk.javabrowser.server.view.IndexView" -->
<!doctype html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport"
        content="width=device-width, user-scalable=no, initial-scale=1.0, maximum-scale=1.0, minimum-scale=1.0">
  <meta http-equiv="X-UA-Compatible" content="ie=edge">
  <title>Java Browser</title>
</head>
<body>
<#if base??>
  <h1>${base}</h1>
</#if>
<#if children?has_content>
  <h2>Artifacts</h2>
  <ul>
    <#list children as child>
      <li><a href="/${prefix}${child}">${child}</a></li>
    </#list>
  </ul>
</#if>
<#if versions?has_content>
  <h2>Versions</h2>
  <ul>
    <#list versions as version>
      <li><a href="/${prefix}${version}">${version}</a></li>
    </#list>
  </ul>
</#if>
</body>
</html>