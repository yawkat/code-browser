<#ftl strip_text=true>
<#-- @ftlvariable name="Modifier" type="java.lang.reflect.Modifier" -->
<#macro typeName type><#-- @ftlvariable name="type" type="at.yawk.javabrowser.BindingDecl.Description.Type" -->${type.simpleName}<#if type.typeParameters?has_content>&lt;<#list type.typeParameters as par><#if !par?is_first>, </#if><@typeName par/></#list>&gt;</#if></#macro>
<#macro decoratedIcon modifiers>
  <span class="declaration-icon"><#t>
    <#nested/>
    <#if Modifier.isFinal(modifiers)>
      <img alt="final" src="/assets/icons/nodes/finalMark.svg">
    </#if>
    <#if Modifier.isStatic(modifiers)>
      <img alt="final" src="/assets/icons/nodes/staticMark.svg">
    </#if>
  </span><#t>
  <#if Modifier.isPrivate(modifiers)>
    <img class="declaration-access" alt="private" src="/assets/icons/nodes/c_private.svg"><#t>
  <#elseif Modifier.isProtected(modifiers)>
    <img class="declaration-access" alt="protected" src="/assets/icons/nodes/c_protected.svg"><#t>
  <#elseif Modifier.isPublic(modifiers)>
    <img class="declaration-access" alt="public" src="/assets/icons/nodes/c_public.svg"><#t>
  <#else>
    <img class="declaration-access" alt="package-private" src="/assets/icons/nodes/c_plocal.svg"><#t>
  </#if>
</#macro>
<#macro type decl>
  <#-- @ftlvariable name="decl" type="at.yawk.javabrowser.server.view.DeclarationNode" -->
  <#-- @ftlvariable name="decl.description" type="at.yawk.javabrowser.BindingDecl.Description.Type" -->
  <@decoratedIcon decl.modifiers>
    <#if decl.description.kind == "ANNOTATION">
      <img alt="annotation" src="/assets/icons/nodes/annotationtype.svg">
    <#elseif decl.description.kind == "CLASS">
      <#if Modifier.isAbstract(decl.modifiers)>
        <img alt="abstract class" src="/assets/icons/nodes/abstractClass.svg">
      <#else>
        <img alt="class" src="/assets/icons/nodes/class.svg">
      </#if>
    <#elseif decl.description.kind == "EXCEPTION">
      <#if Modifier.isAbstract(decl.modifiers)>
        <img alt="abstract exception class" src="/assets/icons/nodes/abstractException.svg">
      <#else>
        <img alt="exception class" src="/assets/icons/nodes/exceptionClass.svg">
      </#if>
    <#elseif decl.description.kind == "ENUM">
      <img alt="enum" src="/assets/icons/nodes/enum.svg">
    <#elseif decl.description.kind == "INTERFACE">
      <img alt="interface" src="/assets/icons/nodes/interface.svg">
    </#if>
  </@decoratedIcon>
  <span class="declaration-name">${decl.description.simpleName}</span><#t>
</#macro>
<#macro field decl>
  <#-- @ftlvariable name="decl" type="at.yawk.javabrowser.server.view.DeclarationNode" -->
  <#-- @ftlvariable name="decl.description" type="at.yawk.javabrowser.BindingDecl.Description.Field" -->
  <@decoratedIcon decl.modifiers>
    <img alt="field" src="/assets/icons/nodes/field.svg">
  </@decoratedIcon>
  <span class="declaration-name"><#t>
    ${decl.description.name}: <@typeName decl.description.typeBinding/><#t>
  </span>
</#macro>
<#macro method decl>
  <@decoratedIcon decl.modifiers>
    <#if Modifier.isAbstract(decl.modifiers)>
      <img alt="abstract method" src="/assets/icons/nodes/abstractMethod.svg">
    <#else>
      <img alt="method" src="/assets/icons/nodes/method.svg">
    </#if>
  </@decoratedIcon>
  <span class="declaration-name"><#t>
    ${decl.description.name}(<#list decl.description.parameterTypeBindings as p><#if !p?is_first>, </#if><@typeName p/></#list>): <@typeName decl.description.returnTypeBinding/><#t>
  </span>
</#macro>
<#macro initializer decl>
  <@decoratedIcon decl.modifiers>
    <img alt="initializer" src="/assets/icons/nodes/classInitializer.svg">
  </@decoratedIcon>
  <span class="declaration-name"><#t>
    <i><#if Modifier.isStatic(decl.modifiers)>static</#if> class initializer</i><#t>
  </span>
</#macro>
<#macro lambda decl>
  <@decoratedIcon decl.modifiers>
    <img alt="initializer" src="/assets/icons/nodes/lambda.svg">
  </@decoratedIcon>
  <span class="declaration-name"><#t>
    <i>Lambda implementing <@typeName decl.description.implementingTypeBinding/></i><#t>
  </span>
</#macro>
<#macro diffIcon node>
  <#if node.diffResult??>
    <span class="decl-diff-icon"><#t>
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

<#macro declarationNode node fullSourceFilePath="" parentBinding="">
  <#-- @ftlvariable name="node" type="at.yawk.javabrowser.server.view.DeclarationNode" -->
  <#local fullSourceFilePath=node.fullSourceFilePath!fullSourceFilePath/>
  <#local canLoadChildren=!(node.children??)/>

  <span class="line<#if node.diffResult??> decl-diff decl-diff-${node.diffResult}</#if>"
          <#if node.kind != "PACKAGE"> data-binding="${node.binding}"</#if>>
    <#if node.children?has_content || canLoadChildren>
      <a href="#" onclick="expandDeclaration(this); return false" class="expander"
      <#if canLoadChildren>
      <#-- TODO: urlencode -->
        data-load-children-from="/declarationTree?artifactId=${node.artifactId}&binding=${node.binding}"
      </#if>
      ></a>
    </#if>

    <#if node.kind == "PACKAGE">
      <@diffIcon node/>
      <img alt="package" src="/assets/icons/nodes/package.svg">
      <span class="declaration-name">${(parentBinding == "")?then(node.binding, node.binding[parentBinding?length+1..])}</span>
    <#else>
      <a href="${fullSourceFilePath}#${node.binding}"><#-- TODO: urlencode --><#t>
        <@diffIcon node/>
        <#if node.kind == "TYPE">
          <@type node/>
        <#elseif node.kind == "LAMBDA">
          <@lambda node/>
        <#elseif node.kind == "INITIALIZER">
          <@initializer node/>
        <#elseif node.kind == "METHOD">
          <@method node/>
        <#elseif node.kind == "FIELD">
          <@field node/>
        </#if>
      </a>
    </#if>
  </span>

  <#if node.children?has_content>
    <ul>
      <@ConservativeLoopBlock iterator=node.children; child>
        <li><@declarationNode child fullSourceFilePath node.binding/></li>
      </@ConservativeLoopBlock>
    </ul>
  </#if>
</#macro>