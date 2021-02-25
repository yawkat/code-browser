<#macro diffIcon node>
    <#if node.diffResult??>
        <span class="diff-icon"><#t>
        <#if node.diffResult == "INSERTION">
            +<#t>
        <#elseif node.diffResult == "DELETION">
            -<#t>
        <#elseif node.diffResult == "CHANGED_INTERNALLY">
            ~<#t>
        </#if>
        </span><#t>
    </#if>
</#macro>