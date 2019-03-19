<#-- @ftlvariable name="" type="at.yawk.javabrowser.server.view.IndexView" -->
<!doctype html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport"
        content="width=device-width, user-scalable=no, initial-scale=1.0, maximum-scale=1.0, minimum-scale=1.0">
  <meta http-equiv="X-UA-Compatible" content="ie=edge">
  <title>Java Browser</title>
  <link rel="stylesheet" href="/assets/shared.css">
  <link rel="stylesheet" href="/assets/index.css">
  <script src="/webjars/zeptojs/1.2.0/zepto.js"></script>
  <script src="/webjars/zeptojs/1.2.0/ajax.js"></script>
  <script src="/webjars/zeptojs/1.2.0/event.js"></script>
  <#if artifactId.id == "">
    <link rel="stylesheet" href="/webjars/font-awesome/5.0.13/web-fonts-with-css/css/fontawesome-all.min.css">
    <link rel="stylesheet" href="/assets/search.css">
    <script src="/assets/app.js"></script>
    <script src="/assets/search.js"></script>
  </#if>
</head>
<body>
<div id="wrapper">
  <div id="header">
    <div>
      <#if artifactId.id == "">
        <a class="search-button" href="javascript:SearchDialog.instance.open()"><i class="fas fa-search"></i></a>
      </#if>
      <h1><#include "path.ftl"></h1>
    </div>
  </div>
  <div id="content">
    <div>
      <h2>Artifacts</h2>
      <ul>
        <#list artifactId.nodes[artifactId.nodes?size - 1].alternatives as child>
          <li><a href="<#if artifactId.id?has_content>/${artifactId.id}</#if>/${child}">${child}</a></li>
        </#list>
      </ul>
    </div>
  </div>
</div>
<#if artifactId.id == "">
    <#include "search-dialog.ftl">
</#if>
</body>
</html>