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
  <link rel="stylesheet" href="/webjars/font-awesome/5.0.13/web-fonts-with-css/css/fontawesome-all.min.css">
  <script src="/webjars/zeptojs/1.2.0/zepto.js"></script>
  <script src="/webjars/zeptojs/1.2.0/ajax.js"></script>
  <script src="/webjars/zeptojs/1.2.0/event.js"></script>
  <script src="/assets/app.js"></script>
  <script src="/assets/search.js"></script>
</head>
<body>
<div id="wrapper">
  <div id="header">
    <div>
      <#if artifactId.id == "">
        <a class="search-button" href="javascript:SearchDialog.instance.open()" title="Hotkey: [T]"><i class="fas fa-search"></i></a>
      </#if>
      <h1><#include "path.ftl"></h1>
    </div>
  </div>
  <div id="content">
    <div>
      <#if artifactId.id == "">
        <div class="message-box">
          <p>This site allows you to explore the source code of the OpenJDK standard library and a selected number of popular maven libraries. Either select an artifact below, or <a href="javascript:SearchDialog.instance.open()">search for a class directly</a>. <br><br>

            <a href="https://github.com/yawkat/java-browser">Contribute on GitHub</a>
          </p>
        </div>
      </#if>

      <#if artifactId.id == "">
        <div class="search-box">
          <input type="text" class="search" autofocus autocomplete="off" data-target="#result-list"
                 placeholder="Search for type…" data-hide-empty>
          <ul id="result-list"></ul>
        </div>
      </#if>

      <h2>Artifacts</h2>
      <ul>
        <#list artifactId.flattenedChildren as child>
          <li><a href="/${child.id}">${child.id}</a></li>
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