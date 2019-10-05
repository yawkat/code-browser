<#ftl strip_text=true>
<#-- @ftlvariable name="Modifier" type="java.lang.reflect.Modifier" -->
<#macro typeName type><#-- @ftlvariable name="type" type="at.yawk.javabrowser.BindingDecl.Description.Type" -->${type.simpleName}<#if type.typeParameters?has_content>&lt;<#list type.typeParameters as par><#if !par?is_first>, </#if><@typeName par/></#list>&gt;</#if></#macro>
<#macro decoratedIcon modifiers>
  <span class="declaration-icon">
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
  <#-- @ftlvariable name="decl" type="at.yawk.javabrowser.BindingDecl" -->
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
  <#-- @ftlvariable name="decl" type="at.yawk.javabrowser.BindingDecl" -->
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

<#macro declarationNode node>
<#-- @ftlvariable name="node" type="at.yawk.javabrowser.server.view.DeclarationNode" -->
  <span class="line">
    <#if node.children?has_content>
      <a href="#" onclick="$(this).closest('li').toggleClass('expanded'); return false"
         class="expander"></a>
    </#if>

    <a href="#${node.declaration.binding}">
      <#if node.descriptionType == "type">
        <@type node.declaration/>
      <#elseif node.descriptionType == "lambda">
        <@lambda node.declaration/>
      <#elseif node.descriptionType == "initializer">
        <@initializer node.declaration/>
      <#elseif node.descriptionType == "method">
        <@method node.declaration/>
      <#elseif node.descriptionType == "field">
        <@field node.declaration/>
      </#if>
    </a>
  </span>

  <#list node.children as child>
    <#if child?is_first><ul></#if>
    <li><@declarationNode child/></li>
    <#if child?is_last></ul></#if>
  </#list>
</#macro>