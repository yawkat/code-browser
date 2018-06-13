<!doctype html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, user-scalable=no, initial-scale=1.0, maximum-scale=1.0, minimum-scale=1.0">
  <meta http-equiv="X-UA-Compatible" content="ie=edge">
  <title>Package index</title>
  <script src="/assets/search.js"></script>
  <link rel="stylesheet" href="/assets/shared.css">
  <link rel="stylesheet" href="/assets/search.css"/>
</head>
<body id="app">
  <div id="wrapper">
    <h1><#include "path.ftl"></h1>

    <div class="search-box">
      <input type="text" class="search" autofocus autocomplete="off" data-target="#result-list" data-artifact-id="${artifactId.artifactId}" data-load-immediately>
      <ul id="result-list"></ul>
    </div>
  </div>
</body>
</html>