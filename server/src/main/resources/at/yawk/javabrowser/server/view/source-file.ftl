<!doctype html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, user-scalable=no, initial-scale=1.0, maximum-scale=1.0, minimum-scale=1.0">
  <meta http-equiv="X-UA-Compatible" content="ie=edge">
  <title>${artifactId.artifactId} : ${sourceFilePath}</title>
  <link rel="stylesheet" href="/assets/shared.css">
  <link rel="stylesheet" href="/assets/code.css">
  <link rel="stylesheet" href="/assets/search.css">
  <link rel="stylesheet" href="/webjars/font-awesome/5.0.13/web-fonts-with-css/css/fontawesome-all.min.css">
  <script src="/webjars/zeptojs/1.2.0/zepto.js"></script>
  <script src="/webjars/zeptojs/1.2.0/ajax.js"></script>
  <script src="/webjars/zeptojs/1.2.0/event.js"></script>
  <script src="/assets/code.js"></script>
  <script src="/assets/search.js"></script>
</head>
<body>
<div id="wrapper">
  <h1><#include "path.ftl"></h1>
  <h2>${sourceFilePath}</h2>
  <code><pre>${codeHtml?no_esc}</pre></code>
</div>

<div class="search-dialog-wrapper">
  <div class="search-dialog search-box">
    <input type="text" class="search" data-artifact-id="${artifactId.artifactId}" data-target="#search-dialog-list" autocomplete="off">
    <ul id="search-dialog-list"></ul>
  </div>
</div>

<div id="tooltip">
</div>
</body>
</html>