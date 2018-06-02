let classes = [];

function commonPrefixLength(a, b, aOff = 0, bOff = 0) {
    let i = 0;
    while (i + aOff < a.length && i + bOff < b.length && a[i + aOff] === b[i + bOff]) {
        i++;
    }
    return i;
}

function matchGroups(groups, query) {
    function impl(groupsI, queryI) {
        if (queryI >= query.length) {
            return [];
        }
        if (groupsI >= groups.length) {
            return null;
        }
        let maxSubstringEnd = queryI + commonPrefixLength(groups[groupsI], query, 0, queryI);
        for (let i = maxSubstringEnd; i >= queryI; i--) {
            const v = memoized(groupsI + 1, i);
            if (v !== null) {
                const next = [i - queryI];
                next.push(...v);
                return next;
            }
        }
        return null;
    }

    const memo = [];

    function memoized(groupsI, queryI) {
        const i = groupsI + queryI * groups.length;
        let v = memo[i];
        if (v === undefined) {
            memo[i] = v = impl(groupsI, queryI);
        }
        return v;
    }

    return memoized(0, 0);
}

function updateSearch() {
    console.log("Updating search...");
    const query = document.querySelector("#search").value.toLowerCase();
    const accept = [];
    for (const c of classes) {
        const match = matchGroups(c.componentsLower, query);
        if (match !== null) {
            let groupCount = 0;
            for (const m of match) {
                if (m > 0) {
                    groupCount++;
                }
            }
            accept.push({
                class: c,
                match: match,
                groupCount: groupCount
            });
        }
    }
    accept.sort((a, b) => {
        if (a.groupCount < b.groupCount) {
            return -1;
        }
        if (a.groupCount > b.groupCount) {
            return 1;
        }
        if (a.class.name.length < b.class.name.length) {
            return -1;
        }
        if (a.class.name.length > b.class.name.length) {
            return 1;
        }
        return 0;
    });
    let html = "";
    for (const a of accept) {
        html += "<li><a href='" + a.class.link + "'>";
        for (let i = 0; i < a.class.components.length; i++) {
            const component = a.class.components[i];
            const bold = a.match[i];
            if (bold > 0) {
                html += "<b>" + component.substring(0, bold) + "</b>";
            }
            html += component.substring(bold);
        }
        html += "</a></li>";
    }
    document.querySelector("#result-list").innerHTML = html;
}

const req = new XMLHttpRequest();
req.onreadystatechange = function () {
    if (req.readyState === XMLHttpRequest.DONE) {
        let resp = JSON.parse(req.responseText);
        for (const c in resp) {
            if (resp.hasOwnProperty(c)) {
                const regex = /(?:\.|([a-z0-9])(?=[A-Z])|([a-zA-Z])(?=[0-9])|$)/g;
                const components = [];
                const componentsLower = [];
                while (true) {
                    const start = regex.lastIndex;
                    const match = regex.exec(c);
                    if (match === null) {
                        break;
                    }
                    const component = c.substring(start, regex.lastIndex);
                    components.push(component);
                    componentsLower.push(component.toLowerCase());
                    if (regex.lastIndex === c.length) {
                        break;
                    }
                }
                classes.push({
                    name: c,
                    link: resp[c],
                    components: components,
                    componentsLower: componentsLower,
                });
            }
        }
        updateSearch();
    }
};
req.open("GET", "package.json", true);
req.send();

window.onload = function () {
    let searchField = document.querySelector("#search");
    searchField.addEventListener('input', updateSearch);
    searchField.addEventListener('keypress', function (e) {
        if ((e.which || e.keyCode) === 13) { // enter
            document.querySelector("li a").click();
            e.preventDefault();
        }
    });
};