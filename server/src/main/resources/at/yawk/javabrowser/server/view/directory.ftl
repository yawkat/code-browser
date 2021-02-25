<#import "diffIcon.ftl" as diffIcon>

<#macro directory entries>
<#-- @ftlvariable name="entries" type="java.util.List<at.yawk.javabrowser.server.view.DirectoryView.DirectoryEntry>" -->
    <ul class="directory">
        <#list entries as entry>
            <li class="<#if entry.diffResult??> decl-diff decl-diff-${entry.diffResult}</#if>">
                <a href="${entry.fullSourceFilePath}">
                    <@diffIcon.diffIcon entry/>
                    <#if entry.directory>
                        <img alt="directory" src="/assets/icons/nodes/folder.svg">
                    <#elseif entry.name?ends_with(".java")>
                        <img alt="java file" src="/assets/icons/fileTypes/java.svg">
                    <#elseif entry.name?ends_with(".class")>
                        <img alt="class file" src="/assets/icons/fileTypes/javaClass.svg">
                    </#if>
                    ${entry.name}
                </a>
            </li>
        </#list>
    </ul>
</#macro>