<#import "declarationNode.ftl" as declarationNode>
<#-- @ftlvariable name="" type="at.yawk.javabrowser.server.view.DeclarationNodeView" -->

<ul>
  <@ConservativeLoopBlock iterator=children; package>
    <li><@declarationNode.declarationNode package "" diffArtifactId/></li>
  </@ConservativeLoopBlock>
</ul>