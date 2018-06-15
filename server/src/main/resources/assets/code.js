window.onload = function () {
    const byId = {};
    let currentLocalSet = [];
    for (const element of document.querySelectorAll(".local-variable")) {
        const id = element.getAttribute("data-local-variable");
        let l = byId[id];
        if (l === undefined) {
            byId[id] = l = [];
        }
        l.push(element);

        element.addEventListener('mouseover', function () {
            for (const other of currentLocalSet) {
                other.classList.remove("local-hover");
            }
            for (const ele of byId[id]) {
                ele.classList.add("local-hover");
            }
            currentLocalSet = byId[id];
        });
        element.addEventListener('mouseout', function () {
            for (const other of currentLocalSet) {
                other.classList.remove("local-hover");
            }
            currentLocalSet = [];
        });
    }
    document.addEventListener('keypress', function (e) {
        if (e.key === "t") {
            openSearch(document.querySelector(".search-dialog-wrapper"));
        }
    });
};

function showReferences(bindingName, superHtml) {
    $.ajax({
        url: '/api/references/' + encodeURIComponent(bindingName),
        dataType: 'json',
        success: function (data) {
            const tooltip = $("#tooltip");

            tooltip.empty();
            if (superHtml) {
                tooltip.append("<b>Super</b>");
                tooltip.append(superHtml);
            }
            let anyItems = false;
            for (const key of Object.keys(data)) {
                if (data[key].length > 0) {
                    anyItems = true;
                    let name;
                    switch (key) {
                        case "UNCLASSIFIED":
                            name = "Unclassified";
                            break;
                        case "SUPER_CONSTRUCTOR_CALL":
                            name = "Super constructor call";
                            break;
                        case "SUPER_METHOD_CALL":
                            name = "Super method call";
                            break;
                        case "METHOD_CALL":
                            name = "Method call";
                            break;
                        case "FIELD_ACCESS":
                            name = "Field access";
                            break;
                        case "SUPER_TYPE":
                            name = "Super type";
                            break;
                        case "SUPER_METHOD":
                            name = "Super method";
                            break;
                        case "JAVADOC":
                            name = "Javadoc";
                            break;
                        case "RETURN_TYPE":
                            name = "Return type";
                            break;
                        case "LOCAL_VARIABLE_TYPE":
                            name = "Local variable type";
                            break;
                        case "PARAMETER_TYPE":
                            name = "Parameter type";
                            break;
                        case "FIELD_TYPE":
                            name = "Field type";
                            break;
                        case "TYPE_CONSTRAINT":
                            name = "Type constraint";
                            break;
                        case "INSTANCE_OF":
                            name = "instanceof";
                            break;
                        case "CAST":
                            name = "Cast";
                            break;
                        case "IMPORT":
                            name = "Import";
                            break;
                        case "ANNOTATION_TYPE":
                            name = "Annotation type";
                            break;
                        case "CONSTRUCTOR_CALL":
                            name = "Constructor call";
                            break;
                        case "THROWS_DECLARATION":
                            name = "Throws declaration";
                            break;
                        case "STATIC_METHOD_CALL_TYPE":
                            name = "Static method call receiver type";
                            break;
                        default:
                            name = key;
                            break
                    }
                    tooltip.append("<span class='reference-type'>" + name + "</span>");
                    let list = $("<ul>");
                    let i = 0;
                    for (const item of data[key]) {
                        if (i === 10) {
                            const expandButton = $("<li><i>More search results</i></li>");
                            expandButton.click(function () {
                                list.find(".hide-search-results").toggle();
                            });
                            list.append(expandButton);
                        }
                        const element = $("<li><a href='" + item.uri + "'>" + item.artifactId + '&nbsp;&nbsp;' + item.sourceFile + ":" + item.line + "</a></li>");
                        if (i >= 10) {
                            element.hide();
                            element.addClass("hide-search-results");
                        }
                        list.append(element);
                        i++;
                    }
                    tooltip.append(list);
                }
            }
            if (!anyItems) {
                tooltip.append("<i>No references found</i>");
            }

            tooltip.show();
            const pos = document.getElementById(bindingName).getBoundingClientRect();
            tooltip.css({
                top: pos.bottom + pageYOffset,
                left: pos.left + pageXOffset
            });
        }
    });

    $(document).click(function (evt) {
        if (!$(evt.target).closest("#tooltip").length) {
            $("#tooltip").hide();
        }
    });
}