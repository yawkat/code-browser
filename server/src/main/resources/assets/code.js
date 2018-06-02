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
};