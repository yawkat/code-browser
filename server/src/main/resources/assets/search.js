"use strict";

class SearchDialog extends Dialog {
    open() {
        super.open();
        this.wrapperElement.querySelector(".search").focus();
    }
}

(function () {
    function updateSearch(items, target) {
        console.log("Updating search...");
        let html = "";
        if (items.length === 0) {
            html = "<li><i>No Results</i></li>";
        }
        for (const a of items) {
            html += "<li><a href='/" + a.artifactId + "/" + a.path + "#" + a.binding + "'>";
            let off = 0;
            for (let i = 0; i < a.components.length; i++) {
                const componentLength = a.components[i];
                const component = a.binding.substring(off, off + componentLength);
                const bold = a.match[i];
                if (bold > 0) {
                    html += "<b>" + component.substring(0, bold) + "</b>";
                }
                html += component.substring(bold);
                off += componentLength;
            }
            html += "</a></li>";
        }
        target.innerHTML = html;
    }

    /**
     * @param {string} query
     * @param {string} realm
     * @param {string} artifactId
     * @param {boolean} includeDependencies
     * @param {function(any, string, XMLHttpRequest): void} consumer
     * @return {XMLHttpRequest}
     */
    function loadQuery(query, realm, artifactId, includeDependencies, consumer) {
        let uri = "/api/search/" + realm + "/" + encodeURIComponent(query) + "?includeDependencies=" + includeDependencies;
        if (artifactId) {
            uri += "&artifactId=" + encodeURIComponent(artifactId);
        }
        return $.ajax({
            url: uri,
            dataType: 'json',
            success: consumer
        });
    }

    $(function () {
        let wrapper = document.getElementById("search-dialog-wrapper");
        if (wrapper) {
            SearchDialog.instance = new SearchDialog(wrapper);

            document.addEventListener('keypress', function (e) {
                if (e.key === "t" && e.target.tagName !== 'INPUT') {
                    SearchDialog.instance.open();
                }
            });
        }

        for (/** @type {HTMLInputElement} */ const searchField of document.querySelectorAll(".search")) {
            const target = document.querySelector(searchField.getAttribute("data-target"));
            const artifactId = searchField.getAttribute("data-artifact-id");
            let realm = searchField.getAttribute("data-realm");
            if (!realm) {
                realm = "source";
            }
            const includeDependencies = searchField.getAttribute("data-include-dependencies") !== "false";
            const hideEmpty = searchField.hasAttribute("data-hide-empty");
            let firstUpdate = true;
            /** @type {null|number} */
            let timer = null;
            /** @type {null|XMLHttpRequest} */
            let request = null;
            const update = function () {
                if (timer !== null) {
                    clearTimeout(timer);
                }
                if (request !== null) {
                    request.abort();
                    request = null;
                }
                searchField.classList.add("loading");
                let f = function () {
                    if (hideEmpty && searchField.value === "") {
                        searchField.classList.remove("loading");
                        target.innerHTML = "";
                    } else {
                        request = loadQuery(searchField.value, realm, artifactId, includeDependencies, function (data) {
                            searchField.classList.remove("loading");
                            updateSearch(data.items, target);
                        });
                    }
                };
                if (firstUpdate) {
                    firstUpdate = false;
                    f();
                } else {
                    timer = setTimeout(f, 200);
                }
            };
            searchField.addEventListener('input', update);
            searchField.addEventListener('focus', function () {
                if (firstUpdate) {
                    update();
                }
            });
            if (searchField.hasAttribute("data-load-immediately")) {
                update();
            }
            searchField.addEventListener('keypress', function (e) {
                if ((e.which || e.keyCode) === 13) { // enter
                    target.querySelector("a").click();
                    e.preventDefault();
                }
            });
        }
    });
})();