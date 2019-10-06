<#import "declarationNode.ftl" as declarationNode>

<ul>
  <@ConservativeLoopBlock iterator=children; package>
    <li><@declarationNode.declarationNode package fullSourceFilePath/></li>
  </@ConservativeLoopBlock>
</ul>