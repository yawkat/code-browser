<#macro diffStats newPath oldPath>
<#-- @ftlvariable name="newPath" type="at.yawk.javabrowser.server.ParsedPath" -->
<#-- @ftlvariable name="oldPath" type="at.yawk.javabrowser.server.ParsedPath" -->
    Showing changes in
    <span class="foreground-new"><b>${newPath.artifact.stringId}</b><#if newPath.sourceFilePath??>/${newPath.sourceFilePath}</#if> (new version)</span> from
    <span class="foreground-old"><b>${oldPath.artifact.stringId}</b><#if oldPath.sourceFilePath??>/${oldPath.sourceFilePath}</#if> (old version)</span>.
</#macro>