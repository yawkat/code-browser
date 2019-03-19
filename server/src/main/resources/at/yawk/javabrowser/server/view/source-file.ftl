<#-- @ftlvariable name="" type="at.yawk.javabrowser.server.view.SourceFileView" -->
<!doctype html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, user-scalable=no, initial-scale=1.0, maximum-scale=1.0, minimum-scale=1.0">
  <meta http-equiv="X-UA-Compatible" content="ie=edge">
  <title>${artifactId.id} : ${sourceFilePathDir}${sourceFilePathFile}</title>
  <link rel="stylesheet" href="/assets/shared.css">
  <link rel="stylesheet" href="/assets/code.css">
  <link rel="stylesheet" href="/assets/search.css">
  <link rel="stylesheet" href="/webjars/font-awesome/5.0.13/web-fonts-with-css/css/fontawesome-all.min.css">
  <script src="/webjars/zeptojs/1.2.0/zepto.js"></script>
  <script src="/webjars/zeptojs/1.2.0/ajax.js"></script>
  <script src="/webjars/zeptojs/1.2.0/event.js"></script>
  <script src="/assets/app.js"></script>
  <script src="/assets/code.js"></script>
  <script src="/assets/search.js"></script>
</head>
<body>
<div id="wrapper">
  <div id="header">
    <div>
      <a class="search-button" href="javascript:SearchDialog.instance.open()"><i class="fas fa-search"></i></a>
      <h1><#include "path.ftl"> <span class="source-file-dir">${sourceFilePathDir}</span>${sourceFilePathFile}</h1>
      <a id="alt-versions" href="javascript:showAlternativeSourceFiles([
      <#list alternatives as alternative>{artifact:'${alternative.artifactId}',path:'${alternative.sourceFilePath}'},</#list>
      ])"></a>
    </div>
  </div>
  <div id="content">
    <div>
      <code><pre>${codeHtml?no_esc}</pre></code>
    </div>
  </div>
</div>

<#include "search-dialog.ftl">

<div id="tooltip">
</div>
</body>
</html>