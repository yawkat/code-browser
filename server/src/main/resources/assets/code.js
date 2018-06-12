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

function showReferences(bindingName) {
    $.ajax({
        url: '/api/references/' + encodeURIComponent(bindingName) + "?limit=5",
        dataType: 'json',
        success: function (data) {
            const tooltip = $("#tooltip");

            tooltip.empty();
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
                    }
                    tooltip.append("<span class='reference-type'>" + name + "</span>");
                    let list = $("<ul>");
                    for (const item of data[key]) {
                        list.append("<li><a href='" + item.uri + "'>" + item.artifactId + '&nbsp;&nbsp;' + item.sourceFile + ":" + item.line + "</a></li>");
                    }
                    tooltip.append(list);
                }
            }
            if (!anyItems) {
                tooltip.html("<i>No references found</i>");
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