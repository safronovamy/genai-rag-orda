// --- Typing Indicator: создаём/удаляем его как отдельное сообщение в чате ---
function showTyping() {
    const box = document.getElementById("chatBox");

    // если индикатор уже есть — не дублируем
    let indicator = document.getElementById("typingIndicator");
    if (!indicator) {
        indicator = document.createElement("div");
        indicator.id = "typingIndicator";
        indicator.classList.add("message", "assistant", "typing-indicator");
        indicator.textContent = "AI is thinking…";
        box.appendChild(indicator);
    }

    box.scrollTop = box.scrollHeight;
}

function hideTyping() {
    const indicator = document.getElementById("typingIndicator");
    if (indicator) {
        indicator.remove();
    }
}

// --- Основная логика чата ---
async function ask() {
    const input = document.getElementById("questionInput");
    const question = input.value.trim();
    if (!question) return;

    // 1. сообщение пользователя
    appendMessage("You: " + question, "user");
    input.value = "";

    // 2. показываем индикатор
    showTyping();

    try {
        const response = await fetch("/api/ask", {
            method: "POST",
            headers: {"Content-Type": "application/json"},
            body: JSON.stringify({ question })
        });

        if (!response.ok) {
            hideTyping();
            const errorText = await response.text();
            appendMessage("AI: Error: " + errorText, "assistant");
            return;
        }

        const data = await response.json();

        // 3. убираем индикатор, когда ответ пришёл
        hideTyping();

        const answer = data.answer ?? JSON.stringify(data, null, 2);
        appendMessage("AI: " + answer, "assistant");
    } catch (e) {
        hideTyping();
        appendMessage("AI: Error: " + e, "assistant");
    }
}

function appendMessage(text, type) {
    const box = document.getElementById("chatBox");
    const div = document.createElement("div");
    div.classList.add("message", type);

    if (type === "assistant") {
        // markdown → HTML (marked подключён в index.html)
        div.innerHTML = marked.parse(text);
    } else {
        div.textContent = text;
    }

    box.appendChild(div);
    box.scrollTop = box.scrollHeight;
}
