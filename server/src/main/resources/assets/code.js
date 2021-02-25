"use strict";

$(function () {
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

    const bindingMenuItems = {};
    for (const element of document.querySelectorAll("#structure .line")) {
        bindingMenuItems[element.getAttribute("data-binding")] = element;
    }

    let selectedMenuElement = null;

    function scrollIfAnchor(firstLoad) {
        const binding = decodeURIComponent(window.location.hash.substring(1));
        const target = document.getElementById(binding);
        if (selectedMenuElement) {
            selectedMenuElement.classList.remove("selected");
            selectedMenuElement = null;
        }
        if (target) {
            /* This can lead to "false positives" when the bar is still on screen even though it's not sticky
            (e.g. on mobile), but the worst that'll happen is that we scroll a bit too far up. */
            const topBarOffset = document.getElementById("header").getBoundingClientRect().bottom;
            if (topBarOffset > 0) {
                let targetTop = target.getBoundingClientRect().top;
                // if this is the first load of this page, check if the browser scrolled to the element on its own. This
                // might not be the case, eg during reload. If it didn't scroll to the element, we don't either.
                if (!firstLoad || (targetTop >= 0 && targetTop < 1)) {
                    window.scrollTo(0, targetTop - topBarOffset + window.pageYOffset);
                }
            }

            selectedMenuElement = bindingMenuItems[binding];
            if (selectedMenuElement) {
                selectedMenuElement.classList.add("selected");
                const rect = selectedMenuElement.getBoundingClientRect();
                if (rect.top < 0 || rect.bottom > window.innerHeight) {
                    selectedMenuElement.scrollIntoView();
                }
            }
        }
    }

    scrollIfAnchor(true);
    window.addEventListener("hashchange", function () {
        scrollIfAnchor(false);
    }, false);
});

const tooltip = {
    /**
     * @type Element
     */
    element: null,
    cancelCallbacks: [],
    show: function (at, content, rightAlign = false) {
        for (const callback of this.cancelCallbacks) {
            callback();
        }
        this.cancelCallbacks = [];

        this.element.innerHTML = "";
        this.element.appendChild(content);

        const pos = at.getBoundingClientRect();
        const wrapperPos = this.element.parentElement.getBoundingClientRect();
        this.element.style.top = (pos.bottom - wrapperPos.top) + "px";
        this.element.style.left = rightAlign ? "auto" : (pos.left - wrapperPos.left) + "px";
        this.element.style.right = rightAlign ? (pos.right - wrapperPos.right) + "px" : "auto";

        this.element.style.display = "block";
    },
    hide: function () {
        for (const callback of this.cancelCallbacks) {
            callback();
        }
        this.cancelCallbacks = [];
        this.element.style.display = "none";
    }
};

$(function () {
    tooltip.element = document.getElementById("tooltip");
});

function showAlternativeSourceFiles(alternativeSourceFiles) {
    const tooltipBody = document.createElement("div");
    if (alternativeSourceFiles) {
        tooltipBody.insertAdjacentHTML("beforeend", "<b>Other versions:</b>");
        const list = document.createElement("ul");
        for (const alternativeSourceFile of alternativeSourceFiles) {
            const artifact = alternativeSourceFile.artifact;
            const path = alternativeSourceFile.path;
            const diffUrl = alternativeSourceFile.diffPath;
            tooltipBody.insertAdjacentHTML("beforeend",
                "<li><a href='" + path + location.hash + "'>" + artifact + "</a> (" +
                (diffUrl ? "<a href='" + diffUrl + "'>diff</a>" : "current") + ")</li>"
            );
        }
        tooltipBody.append(list);
    } else {
        tooltipBody.insertAdjacentHTML("beforeend", "<i>No alternative versions</i>");
    }
    tooltip.show(document.getElementById("alt-versions").parentElement, tooltipBody, true);
}

function showReferences(targetElement) {
    const LIMIT = 100;

    const realm = targetElement.getAttribute("data-realm");
    const bindingName = targetElement.getAttribute("data-binding");
    const superHtml = targetElement.getAttribute("data-super-html");
    const targetArtifactId = targetElement.getAttribute("data-artifact-id");

    const tooltipBody = document.createElement("div");

    if (superHtml) {
        tooltipBody.insertAdjacentHTML("beforeend", "<b>Extends</b>");
        tooltipBody.insertAdjacentHTML("beforeend", superHtml);
        tooltipBody.insertAdjacentHTML("beforeend", "<br>");
    }

    const loading = document.createElement("i");
    loading.textContent = "Loading…";
    tooltipBody.appendChild(loading);

    tooltip.show(targetElement, tooltipBody);

    const xhr = $.ajax({
        url: '/api/references/' + realm + '/' + encodeURIComponent(bindingName) + "?limit=" + LIMIT + "&targetArtifactId=" + encodeURIComponent(targetArtifactId),
        dataType: 'json',
        success: function (data) {
            loading.remove();

            let anyItems = false;
            for (const key of Object.keys(data)) {
                if (data[key].length > 0) {
                    if (!anyItems) {
                        tooltipBody.insertAdjacentHTML("beforeend", "<b><a href='/references/" + realm + '/' + encodeURIComponent(bindingName) + "'>Show all (new page)</a></b><br>");
                    }
                    anyItems = true;
                    let name;
                    switch (key) {
                        case "UNCLASSIFIED":
                            name = "Unclassified";
                            break;
                        case "SUPER_CONSTRUCTOR_CALL":
                            name = "Super constructor call from";
                            break;
                        case "SUPER_METHOD_CALL":
                            name = "Super method call from";
                            break;
                        case "METHOD_CALL":
                            name = "Method call from";
                            break;
                        case "FIELD_ACCESS":
                            name = "Field access";
                            break;
                        case "FIELD_READ":
                            name = "Field read";
                            break;
                        case "FIELD_WRITE":
                            name = "Field write";
                            break;
                        case "FIELD_READ_WRITE":
                            name = "Field read+write";
                            break;
                        case "SUPER_TYPE":
                            name = "Super type of";
                            break;
                        case "SUPER_METHOD":
                            name = "Super method of";
                            break;
                        case "JAVADOC":
                            name = "Javadoc";
                            break;
                        case "RETURN_TYPE":
                            name = "Return type of";
                            break;
                        case "LOCAL_VARIABLE_TYPE":
                            name = "Local variable type of";
                            break;
                        case "PARAMETER_TYPE":
                            name = "Parameter type of";
                            break;
                        case "FIELD_TYPE":
                            name = "Field type of";
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
                            name = "Import from";
                            break;
                        case "ANNOTATION_TYPE":
                            name = "Annotation type at";
                            break;
                        case "CONSTRUCTOR_CALL":
                            name = "Constructor call from";
                            break;
                        case "THROWS_DECLARATION":
                            name = "Throws declaration at";
                            break;
                        case "STATIC_MEMBER_QUALIFIER":
                            name = "Static member qualifier at";
                            break;
                        case "NESTED_CLASS_QUALIFIER":
                            name = "Nested class qualifier at";
                            break;
                        case "METHOD_REFERENCE_RECEIVER_TYPE":
                            name = "Method reference receiver type at";
                            break;
                        case "TYPE_PARAMETER":
                            name = "Type parameter at";
                            break;
                        case "WILDCARD_BOUND":
                            name = "Wildcard bound at";
                            break;
                        case "THIS_REFERENCE_QUALIFIER":
                            name = "this reference qualifier at";
                            break;
                        case "SUPER_REFERENCE_QUALIFIER":
                            name = "super reference qualifier at";
                            break;
                        case "ANNOTATION_MEMBER_VALUE":
                            name = "Annotation value at";
                            break;
                        default:
                            name = key;
                            break;
                    }
                    tooltipBody.insertAdjacentHTML("beforeend", "<span class='reference-type'>" + name + "</span>");
                    let list = document.createElement("ul");
                    let i = 0;
                    for (const item of data[key]) {
                        if (i === 10) {
                            const expandButton = document.createElement("li");
                            expandButton.innerHTML = "<i>More search results</i>";
                            expandButton.onclick = function () {
                                list.querySelector(".hide-search-results").classList.toggle("hide");
                            };
                            list.append(expandButton);
                        }
                        if (i === LIMIT) {
                            let showAll = document.createElement("li");
                            showAll.innerHTML = "<b><a href='/references/" + encodeURIComponent(bindingName) + "?type=" + key + "'>Show all (new page)</a></b>";
                            showAll.classList.add("hide", "hide-search-results");
                            list.append(showAll);
                            break;
                        }
                        const element = document.createElement("li");
                        element.innerHTML = "<a href='" + item.uri + "'>" + item.artifactId + '&nbsp;&nbsp;' + item.sourceFile + ":" + item.line + "</a>";
                        if (i >= 10) {
                            element.classList.add("hide", "hide-search-results");
                        }
                        list.append(element);
                        i++;
                    }
                    tooltipBody.append(list);
                }
            }
            if (!anyItems) {
                tooltipBody.insertAdjacentHTML("beforeend", "<i>No references found</i>");
            }
        }
    });

    tooltip.cancelCallbacks.push(function () { xhr.abort(); });
}

document.addEventListener("click", function (evt) {
    if (!tooltip.element.contains(evt.target)) {
        tooltip.hide()
    }
});