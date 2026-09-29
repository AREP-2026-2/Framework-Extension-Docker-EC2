function greet() {
    const name = document.getElementById("name").value;

    fetch("/hello?name=" + encodeURIComponent(name))
        .then(response => response.text())
        .then(message => {
            document.getElementById("result").innerHTML = message;
        })
        .catch(error => {
            document.getElementById("result").innerHTML = "Error: " + error;
        });
}

function fetchPi() {
    fetch("/pi")
        .then(response => response.text())
        .then(value => {
            document.getElementById("pi-result").innerHTML = value;
        })
        .catch(error => {
            document.getElementById("pi-result").innerHTML = "Error: " + error;
        });
}
