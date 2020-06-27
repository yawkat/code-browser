package at.yawk.javabrowser.server

import at.yawk.javabrowser.server.view.View
import org.apache.commons.io.output.NullOutputStream
import org.hamcrest.BaseMatcher
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.skife.jdbi.v2.DBI
import org.testng.SkipException

fun loadIntegrationTestDbi(dataSource: String?): DBI {
    if (dataSource == null) throw SkipException("Missing dataSource parameter")
    return DBI(dataSource)
}

inline fun <reified T> matches(crossinline pred: (T) -> Boolean): Matcher<T> = object : BaseMatcher<T>() {
    override fun describeTo(description: Description) {
        description.appendText("Matches lambda")
    }

    override fun matches(item: Any?) = item is T && pred(item)
}

fun tryRender(view: View) {
    Ftl(ImageCache()).render(view, NullOutputStream(), theme = null)
}
