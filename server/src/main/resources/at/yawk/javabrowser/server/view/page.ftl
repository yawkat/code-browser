<#ftl strip_text=true>
<#import "path.ftl" as path>
<#macro page artifactId title hasSearch=false additionalTitle="" additionalMenu="">
  <!doctype html>
  <html lang="en">
  <head>
    <meta charset="UTF-8">
    <meta name="viewport"
          content="width=device-width, user-scalable=no, initial-scale=1.0, maximum-scale=1.0, minimum-scale=1.0">
    <meta http-equiv="X-UA-Compatible" content="ie=edge">
    <title>${title}</title>
    <link rel="stylesheet" href="/assets/shared.css">
    <link rel="stylesheet" href="/webjars/font-awesome/5.0.13/web-fonts-with-css/css/fontawesome-all.min.css">
    <script src="/webjars/zeptojs/1.2.0/zepto.js"></script>
    <script src="/webjars/zeptojs/1.2.0/ajax.js"></script>
    <script src="/webjars/zeptojs/1.2.0/event.js"></script>
    <script src="/assets/app.js"></script>
    <script src="/assets/search.js"></script>
    <script src="/assets/code.js"></script>
  </head>
  <body>
  <div id="wrapper">
    <div id="header">
      <div>
        <#if hasSearch>
          <a class="search-button" href="javascript:SearchDialog.instance.open()" title="Hotkey: [T]"><i class="fas fa-search"></i></a>
        </#if>
        <h1>
          <#if artifactId?has_content><@path.showNode artifactId/></#if>
          ${additionalTitle}</h1> ${additionalMenu}
      </div>
    </div>
    <div id="content">
      <div>
        <#nested/>
      </div>
    </div>
    <#if hasSearch>
      <#include "search-dialog.ftl">
    </#if>
  </body>
  </html>
</#macro>