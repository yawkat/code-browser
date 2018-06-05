<!doctype html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, user-scalable=no, initial-scale=1.0, maximum-scale=1.0, minimum-scale=1.0">
  <meta http-equiv="X-UA-Compatible" content="ie=edge">
  <title>${artifactId} : ${sourceFilePath}</title>
  <link rel="stylesheet" href="/assets/code.css">
  <link rel="stylesheet" href="/assets/search.css">
  <script src="/assets/code.js"></script>
  <script src="/assets/search.js"></script>
</head>
<body>
<code><pre>${codeHtml?no_esc}</pre></code>

<div class="search-dialog-wrapper">
  <div class="search-dialog search-box">
    <input type="text" class="search" data-artifact-id="${artifactId}" data-target="#search-dialog-list" autocomplete="off">
    <ul id="search-dialog-list"></ul>
  </div>
</div>
</body>
</html>