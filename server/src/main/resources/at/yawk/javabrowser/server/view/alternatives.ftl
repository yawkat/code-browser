<#macro alternatives alternatives>
<#-- @ftlvariable name="alternatives" type="java.util.List<at.yawk.javabrowser.server.view.Alternative>" -->
    <a id="alt-versions" href="javascript:showAlternativeSourceFiles([
      <#list alternatives as alternative>{artifact:'${alternative.artifact.stringId}',path:'${alternative.path}'<#if alternative.diffPath??>,diffPath:'${alternative.diffPath}'</#if>},</#list>
      ])"><i class="ij ij-history"></i></a>
</#macro>