package at.yawk.javabrowser.server

import freemarker.core.Environment
import freemarker.ext.util.WrapperTemplateModel
import freemarker.template.TemplateBooleanModel
import freemarker.template.TemplateDirectiveBody
import freemarker.template.TemplateDirectiveModel
import freemarker.template.TemplateModel
import freemarker.template.TemplateModelException

/**
 * Freemarker loop block for Iterators that does not use previous elements, hasNext etc.
 *
 * @author yawkat
 */
class ConservativeLoopBlock : TemplateDirectiveModel {
    override fun execute(
            env: Environment,
            params: MutableMap<Any?, Any?>,
            loopVars: Array<TemplateModel?>,
            body: TemplateDirectiveBody?) {
        val iterator = (params["iterator"] as? WrapperTemplateModel)?.wrappedObject as? Iterator<Any?>
                ?: throw TemplateModelException("`iterator` must be an Iterator")
        val skipNull = params["skipNull"] != null && (params["skipNull"] as TemplateBooleanModel).asBoolean
        if (body != null) {
            for (item in iterator) {
                if (!skipNull || item != null) {
                    loopVars[0] = env.objectWrapper.wrap(item)
                    body.render(env.out)
                }
            }
        }
    }
}