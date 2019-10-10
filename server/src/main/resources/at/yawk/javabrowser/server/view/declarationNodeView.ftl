<#import "declarationNode.ftl" as declarationNode>
<#-- @ftlvariable name="" type="at.yawk.javabrowser.server.view.DeclarationNodeView" -->

<ul>
  <@ConservativeLoopBlock iterator=children; package>
    <li><@declarationNode.declarationNode node=package fullSourceFilePath="" parentBinding=parentBinding diffArtifactId=diffArtifactId/></li>
  </@ConservativeLoopBlock>
</ul>