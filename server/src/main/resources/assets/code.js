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
        url: '/api/references/' + encodeURI(bindingName),
        dataType: 'json',
        success: function (data) {
            const tooltip = $("#tooltip");

            tooltip.empty();
            let anyItems = false;
            for (const key of Object.keys(data)) {
                if (data[key].length > 0) {
                    anyItems = true;
                    tooltip.append(key);
                    let list = $("<ul>");
                    for (const item of data[key]) {
                        list.append("<a href='" + item.uri + "'>" + item.artifactId + '/' + item.sourceFile + ":" + item.line + "</a>");
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
}