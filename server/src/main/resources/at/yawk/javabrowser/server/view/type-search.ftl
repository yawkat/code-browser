<#-- @ftlvariable name="" type="at.yawk.javabrowser.server.view.TypeSearchView" -->
<!doctype html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, user-scalable=no, initial-scale=1.0, maximum-scale=1.0, minimum-scale=1.0">
  <meta http-equiv="X-UA-Compatible" content="ie=edge">
  <title>Package index</title>
  <link rel="stylesheet" href="/assets/shared.css">
  <link rel="stylesheet" href="/assets/search.css">
  <link rel="stylesheet" href="/webjars/font-awesome/5.0.13/web-fonts-with-css/css/fontawesome-all.min.css">
  <link rel="stylesheet" href="/assets/search.css">
  <script src="/webjars/zeptojs/1.2.0/zepto.js"></script>
  <script src="/webjars/zeptojs/1.2.0/ajax.js"></script>
  <script src="/webjars/zeptojs/1.2.0/event.js"></script>
  <script src="/assets/app.js"></script>
  <script src="/assets/search.js"></script>
</head>
<body id="app">
<div id="wrapper">
  <div id="header">
    <div>
      <h1><#include "path.ftl"></h1>
    </div>
  </div>

  <div id="content">
    <div>
      <#include "metadata.ftl">

      <#if dependencies?has_content>
        <h2>Dependencies</h2>
        <ul>
          <#list dependencies as dependency>
            <li>
              <#if dependency.prefix??>
                <a href="/${dependency.prefix}">${dependency.prefix}</a></#if>${dependency.suffix}
            </li>
          </#list>
        </ul>
      </#if>

      <div class="search-box">
        <input type="text" class="search" autofocus autocomplete="off" data-target="#result-list" data-artifact-id="${artifactId.id}" data-include-dependencies="false" data-load-immediately placeholder="Search for type…">
        <ul id="result-list"></ul>
      </div>
    </div>
  </div>
</div>
</body>
</html>