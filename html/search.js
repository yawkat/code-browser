let classes = {};
let classNames = [];

function updateSearch() {
    let html = "";
    const query = document.querySelector("#search").value.toLowerCase();
    for (const c of classNames) {
        if (c.toLowerCase().indexOf(query) !== -1) {
            html += "<li><a href='" + classes[c] + "'>" + c + "</a></li>"
        }
    }
    document.querySelector("#result-list").innerHTML = html;
}

const req = new XMLHttpRequest();
req.onreadystatechange = function () {
    if (req.readyState === XMLHttpRequest.DONE) {
        classes = JSON.parse(req.responseText);
        for (const c in classes) {
            if (classes.hasOwnProperty(c)) {
                classNames.push(c);
            }
        }
        classNames.sort();
        updateSearch();
    }
};
req.open("GET", "package.json", true);
req.send();

window.onload = function () {
    document.querySelector("#search").onkeypress = updateSearch;
};