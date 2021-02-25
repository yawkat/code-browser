<#ftl strip_text=true>
<#-- @ftlvariable name="" type="at.yawk.javabrowser.server.view.DirectoryView" -->
<#import "page.ftl" as page>
<#import "alternatives.ftl" as at>
<#import "directory.ftl" as directory>
<#import "diffStats.ftl" as diffStats>

<#assign additionalMenu>
    <@at.alternatives alternatives/>
</#assign>
<@page.page title="${newPath.artifact.stringId} : ${newPath.sourceFilePath}" realm=realm newPath=newPath oldPath=oldPath! hasSearch=true additionalTitle=additionalTitle additionalMenu=additionalMenu narrow=true tooltip=true>
    <#if oldPath??>
        <@diffStats.diffStats newPath oldPath/>
    </#if>

    <@directory.directory entries/>
</@page.page>