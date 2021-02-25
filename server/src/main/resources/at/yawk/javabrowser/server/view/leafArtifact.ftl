<#ftl strip_text=true>
<#-- @ftlvariable name="" type="at.yawk.javabrowser.server.view.LeafArtifactView" -->
<#import "page.ftl" as page>
<#import "declarationNode.ftl" as declarationNode>
<#import "fullTextSearchForm.ftl" as ftsf>
<#import "alternatives.ftl" as at>
<#import "directory.ftl" as directory>
<#import "diffStats.ftl" as diffStats>

<#assign additionalMenu>
    <@at.alternatives alternatives/>
</#assign>
<@page.page title="${path.artifact.stringId}" realm='source' newPath=path oldPath=oldPath! additionalMenu=additionalMenu narrow=true tooltip=true>
    <div>
        <#include "metadata.ftl">

        <#if oldPath??>
            <@diffStats.diffStats path oldPath/>
        </#if>

        <#if !oldPath??>
            <@ftsf.fullTextSearchForm query='' searchArtifact=path.artifact/>
            <div class="search-box">
                <div class="search-spinner-wrapper">
                    <input type="text" class="search" autocomplete="off" data-target="#result-list" data-hide-empty data-realm="source" data-artifact-id="${path.artifact.stringId}" data-include-dependencies="false" placeholder="Search for type…">
                    <div class="spinner"></div>
                </div>
                <ul id="result-list"></ul>
            </div>
        </#if>

        <#if dependencies?has_content>
            <details>
                <summary><img alt="directory" src="/assets/icons/nodes/ppLib.svg"> Dependencies</summary>
                <div>
                    <ul>
                        <#list dependencies as dependency>
                            <li>
                                <#if dependency.prefix??>
                                    <a href="/${dependency.prefix}">${dependency.prefix}</a></#if>${dependency.suffix}
                                <#if dependency.aliasedTo??>(available as
                                    <a href="/${dependency.aliasedTo}">${dependency.aliasedTo}</a>)</#if>
                            </li>
                        </#list>
                    </ul>
                </div>
            </details>
        </#if>
        <details>
            <summary><img alt="directory" src="/assets/icons/nodes/class.svg"> Declarations</summary>
            <div>
                <div class="declaration-tree">
                    <ul>
                        <#if oldPath??>
                            <#assign diffArtifactId=oldPath.artifact.stringId>
                        <#else>
                            <#assign diffArtifactId="">
                        </#if>
                        <@ConservativeLoopBlock iterator=topLevelPackages; package>
                            <li><@declarationNode.declarationNode node=package diffArtifactId=diffArtifactId/></li>
                        </@ConservativeLoopBlock>
                    </ul>
                </div>
            </div>
        </details>
        <details>
            <summary><img alt="directory" src="/assets/icons/fileTypes/java.svg"> Java files</summary>
            <div>
                <@directory.directory topDirectoryEntries["SOURCE"]/>
            </div>
        </details>
        <details>
            <summary><img alt="directory" src="/assets/icons/fileTypes/javaClass.svg"> Class files</summary>
            <div>
                <@directory.directory topDirectoryEntries["BYTECODE"]/>
            </div>
        </details>
    </div>
</@page.page>