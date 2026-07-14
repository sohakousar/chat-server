// Configuration and State
const SERVER_URL = 'ws://localhost:8887';
let socket = null;
let myUsername = '';

// DOM Elements
const loginOverlay = document.getElementById('login-overlay');
const loginError = document.getElementById('login-error');

const roomOverlay = document.getElementById('room-overlay');
const roomListContainer = document.getElementById('room-list-container');
const roomNameDisplay = document.getElementById('room-name-display');
const switchRoomBtn = document.getElementById('switch-room-btn');

const chatApp = document.getElementById('chat-app');
const myAvatar = document.getElementById('my-avatar');
const myUsernameDisplay = document.getElementById('my-username');
const userCountBadge = document.getElementById('user-count');
const onlineUsersList = document.getElementById('online-users-list');
const logoutBtn = document.getElementById('logout-btn');

const sidebarToggle = document.getElementById('sidebar-toggle');
const sidebar = document.querySelector('.sidebar');

const reconnectBanner = document.getElementById('reconnecting-banner');
const reconnectText = document.getElementById('reconnect-text');

// Reconnection States
let storedIdToken = '';
let currentRoomName = '';
let isIntentionalDisconnect = false;
let reconnectAttempts = 0;
let reconnectDelay = 1000;
const maxReconnectAttempts = 10;
let reconnectTimeoutId = null;

const chatMessages = document.getElementById('chat-messages');
const chatForm = document.getElementById('chat-form');
const chatInput = document.getElementById('chat-input');
const charCounter = document.getElementById('char-counter');
const chatSendBtn = document.getElementById('chat-send-btn');

// --- Helper Functions ---

/**
 * Escapes special characters to prevent XSS injection attacks.
 */
function escapeHTML(str) {
    return str.replace(/[&<>'"]/g,
        tag => ({
            '&': '&amp;',
            '<': '&lt;',
            '>': '&gt;',
            "'": '&#39;',
            '"': '&quot;'
        }[tag] || tag)
    );
}

/**
 * Formats an ISO timestamp string into a localized time string (HH:MM AM/PM).
 */
function formatTime(isoString) {
    try {
        const date = new Date(isoString);
        return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    } catch (e) {
        return new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    }
}

/**
 * Scrolls the message viewport smoothly to the bottom.
 */
function scrollToBottom() {
    chatMessages.scrollTo({
        top: chatMessages.scrollHeight,
        behavior: 'smooth'
    });
}

/**
 * Renders user avatars using their initials.
 */
function getAvatarInitial(name) {
    return name ? name.trim().charAt(0).toUpperCase() : '?';
}

/**
 * Displays error messages on the login card.
 */
function showAuthError(message) {
    loginError.textContent = message;
    loginError.classList.remove('hidden');
}

// --- WebSocket Event Handlers ---

function handleAuthSuccess(displayName) {
    console.log("AUTH_SUCCESS received");
    myUsername = displayName;
    myUsernameDisplay.textContent = displayName;
    myAvatar.textContent = getAvatarInitial(displayName);

    loginOverlay.classList.add('hidden');

    if (reconnectAttempts > 0 && currentRoomName) {
        console.log(`Reconnected successfully. Re-joining room: ${currentRoomName}`);
        joinRoom(currentRoomName);
        reconnectAttempts = 0;
        reconnectDelay = 1000;
        hideReconnectBanner();
    } else {
        roomOverlay.classList.remove('hidden');
        reconnectAttempts = 0;
        reconnectDelay = 1000;
        hideReconnectBanner();
    }
}

function renderRoomSelector(rooms) {
    roomListContainer.innerHTML = '';
    rooms.forEach(room => {
        const btn = document.createElement('button');
        btn.className = 'room-btn';
        btn.innerHTML = `<span class="room-hash">#</span> ${escapeHTML(room)}`;
        btn.addEventListener('click', () => {
            joinRoom(room);
        });
        roomListContainer.appendChild(btn);
    });
}

function joinRoom(room) {
    if (!socket || socket.readyState !== WebSocket.OPEN) {
        return;
    }
    currentRoomName = room;
    resetChatView(room);
    const joinMsg = {
        sender: myUsername,
        room: room,
        timestamp: new Date().toISOString(),
        type: 'JOIN_ROOM'
    };
    socket.send(JSON.stringify(joinMsg));

    // Hide room overlay and show chat app
    roomOverlay.classList.add('hidden');
    chatApp.classList.remove('hidden');

    // Update room name display
    roomNameDisplay.textContent = room + "-chat";
    chatInput.focus();
}

function switchRoom() {
    if (!socket || socket.readyState !== WebSocket.OPEN) {
        return;
    }
    currentRoomName = '';
    const leaveMsg = {
        sender: myUsername,
        timestamp: new Date().toISOString(),
        type: 'LEAVE_ROOM'
    };
    socket.send(JSON.stringify(leaveMsg));

    // Reset layout
    resetChatView('general');

    // Hide chat app and show room overlay
    chatApp.classList.add('hidden');
    roomOverlay.classList.remove('hidden');
}

function resetChatView(room) {
    chatMessages.innerHTML = `
        <div class="welcome-box">
            <div class="welcome-icon">👋</div>
            <h2>Welcome to the ${escapeHTML(room)} room!</h2>
            <p>This is the start of the ${escapeHTML(room)} room. Enjoy your conversations in real time.</p>
        </div>
    `;
    onlineUsersList.innerHTML = '';
    userCountBadge.textContent = '0';
    chatInput.value = '';
    charCounter.textContent = '0/500';
    chatSendBtn.disabled = true;
}

function showReconnectBanner(text) {
    reconnectText.textContent = text;
    reconnectBanner.classList.remove('hidden');
}

function hideReconnectBanner() {
    reconnectBanner.classList.add('hidden');
}

function triggerReconnection() {
    if (isIntentionalDisconnect || !storedIdToken) {
        return;
    }

    if (reconnectAttempts >= maxReconnectAttempts) {
        console.log("Max reconnect attempts reached. Giving up.");
        hideReconnectBanner();
        showAuthError("Connection lost permanently. Please refresh the page manually.");
        resetAppState();
        return;
    }

    reconnectAttempts++;
    console.log(`Reconnection attempt ${reconnectAttempts} of ${maxReconnectAttempts} in ${reconnectDelay}ms...`);

    showReconnectBanner(`Reconnecting... (Attempt ${reconnectAttempts}/${maxReconnectAttempts})`);

    if (reconnectTimeoutId) {
        clearTimeout(reconnectTimeoutId);
    }

    reconnectTimeoutId = setTimeout(() => {
        initWebSocket(storedIdToken);
        // Exponential backoff: double the delay up to 30s
        reconnectDelay = Math.min(reconnectDelay * 2, 30000);
    }, reconnectDelay);
}

function initWebSocket(idToken) {
    loginError.classList.add('hidden');

    if (!socket || socket.readyState !== WebSocket.OPEN) {
        // If a socket exists but is not open (e.g., connecting or closing), close it first
        if (socket) {
            try {
                socket.close();
            } catch (e) {
                // Ignore close errors
            }
        }

        try {
            socket = new WebSocket(SERVER_URL);
        } catch (e) {
            showAuthError('Could not establish connection to the server.');
            return;
        }

        socket.onopen = function () {
            console.log("WebSocket connection opened. Sending auth request...");
            sendAuthRequest(idToken);
        };

        socket.onmessage = function (event) {
            try {
                const msg = JSON.parse(event.data);
                if (msg.type === 'AUTH_SUCCESS') {
                    const displayName = msg.text || 'Google User';
                    handleAuthSuccess(displayName);
                } else if (msg.type === 'ROOM_LIST') {
                    renderRoomSelector(msg.rooms);
                } else if (msg.type === 'AUTH_FAILURE') {
                    console.error("Auth failure message received from server:", msg.text);
                    showAuthError(msg.text || 'Authentication failed.');
                    isIntentionalDisconnect = true;
                    storedIdToken = '';
                    currentRoomName = '';
                    hideReconnectBanner();
                    socket.close();
                } else {
                    handleIncomingMessage(msg);
                }
            } catch (e) {
                console.error('Error parsing incoming message: ', e, event.data);
            }
        };

        socket.onclose = function (event) {
            resetAppState();
            if (isIntentionalDisconnect) {
                if (event.reason) {
                    showAuthError(`Disconnected: ${event.reason}`);
                } else if (!event.wasClean) {
                    showAuthError('Lost connection to server.');
                }
            } else {
                // Unexpected drop: trigger reconnect
                triggerReconnection();
            }
        };

        socket.onerror = function (err) {
            console.error('Socket error: ', err);
            if (isIntentionalDisconnect) {
                showAuthError('A network error occurred.');
            }
        };
    } else {
        console.log("WebSocket is already open. Sending auth request...");
        sendAuthRequest(idToken);
    }
}

function sendAuthRequest(idToken) {
    const authMsg = {
        sender: 'GoogleClient',
        idToken: idToken,
        timestamp: new Date().toISOString(),
        type: 'GOOGLE_AUTH'
    };
    console.log("Sending GOOGLE_AUTH message", authMsg);
    socket.send(JSON.stringify(authMsg));
    console.log("GOOGLE_AUTH message sent");
}

/**
 * Directs processing logic based on the message type.
 */
function handleIncomingMessage(msg) {
    if (msg.type === 'JOIN' || msg.type === 'LEAVE') {
        // 1. Append system join/leave alert
        appendSystemMessage(msg.text);

        // 2. Update active user list sidebar if payload includes users array
        if (msg.users && Array.isArray(msg.users)) {
            updateOnlineUsers(msg.users);
        }
    } else if (msg.type === 'CHAT') {
        appendChatBubble(msg);
    }
}

// --- DOM Rendering Actions ---

/**
 * Appends a centered system notification message.
 */
function appendSystemMessage(text) {
    const el = document.createElement('div');
    el.className = 'system-message';
    el.textContent = text;
    chatMessages.appendChild(el);
    scrollToBottom();
}

/**
 * Appends a structured message card bubble.
 */
function appendChatBubble(msg) {
    const isSelf = msg.sender.toLowerCase() === myUsername.toLowerCase();

    const wrapper = document.createElement('div');
    wrapper.className = `message-wrapper ${isSelf ? 'self' : 'other'}`;

    let metaHTML = '';
    if (!isSelf) {
        // Show sender's name above message bubbles from other users
        metaHTML = `<div class="message-meta">${escapeHTML(msg.sender)}</div>`;
    }

    const escapedText = escapeHTML(msg.text);
    const formattedTime = formatTime(msg.timestamp);

    wrapper.innerHTML = `
        ${metaHTML}
        <div class="message-bubble">
            <span class="message-text">${escapedText}</span>
            <span class="message-timestamp">${formattedTime}</span>
        </div>
    `;

    chatMessages.appendChild(wrapper);
    scrollToBottom();
}

/**
 * Re-renders the online users sidebar.
 */
function updateOnlineUsers(users) {
    // Update count badge
    userCountBadge.textContent = users.length;

    // Clear current list
    onlineUsersList.innerHTML = '';

    // Sort alphabetically so the order is clean and predictable
    users.sort((a, b) => a.localeCompare(b));

    users.forEach(user => {
        const li = document.createElement('li');
        const isMe = user.toLowerCase() === myUsername.toLowerCase();

        li.innerHTML = `
            <div class="user-avatar-small" style="background-color: ${isMe ? 'var(--bg-bubble-self)' : 'var(--bg-bubble-other)'}">
                ${getAvatarInitial(user)}
            </div>
            <span>${escapeHTML(user)} ${isMe ? '<small style="color: var(--text-muted); font-size: 0.75rem;">(You)</small>' : ''}</span>
        `;
        onlineUsersList.appendChild(li);
    });
}

/**
 * Resets memory, clears forms, and reveals the username card overlay.
 */
function resetAppState() {
    myUsername = '';
    socket = null;
    chatApp.classList.add('hidden');
    roomOverlay.classList.add('hidden');
    loginOverlay.classList.remove('hidden');

    // Clear viewport back to welcome message
    resetChatView('general');

    if (isIntentionalDisconnect) {
        hideReconnectBanner();
        reconnectAttempts = 0;
        reconnectDelay = 1000;
        if (reconnectTimeoutId) {
            clearTimeout(reconnectTimeoutId);
            reconnectTimeoutId = null;
        }
    }
}

// --- Interface Interactions & Inputs ---

window.onload = function () {
    google.accounts.id.initialize({
        client_id: "587631296834-i27jk1h87l360lpruutoal7t8optlrse.apps.googleusercontent.com",
        callback: handleGoogleSignIn
    });
    google.accounts.id.renderButton(
        document.getElementById("google-signin-button"),
        { theme: "filled_black", size: "large", width: 300 }
    );
};

function handleGoogleSignIn(response) {
    console.log("Google callback triggered", response);
    if (response && response.credential) {
        storedIdToken = response.credential;
        isIntentionalDisconnect = false;
        reconnectAttempts = 0;
        reconnectDelay = 1000;
        if (reconnectTimeoutId) {
            clearTimeout(reconnectTimeoutId);
            reconnectTimeoutId = null;
        }
        initWebSocket(response.credential);
    } else {
        showAuthError("Google Sign-In failed.");
    }
}

// Chat submit
chatForm.addEventListener('submit', function (e) {
    e.preventDefault();
    if (!socket || socket.readyState !== WebSocket.OPEN) {
        return;
    }

    let text = chatInput.value.trim();
    if (text.length === 0) {
        return;
    }

    // Enforce char limit
    if (text.length > 500) {
        text = text.substring(0, 500);
    }

    const chatMsg = {
        sender: myUsername,
        text: text,
        timestamp: new Date().toISOString(),
        type: 'CHAT'
    };

    socket.send(JSON.stringify(chatMsg));

    // Reset input states
    chatInput.value = '';
    charCounter.textContent = '0/500';
    charCounter.className = 'char-count';
    chatSendBtn.disabled = true;
    chatInput.focus();
});

// Counter updating & send button control
chatInput.addEventListener('input', function () {
    const len = chatInput.value.length;
    charCounter.textContent = `${len}/500`;

    // Visual indicators for length
    if (len > 450 && len <= 500) {
        charCounter.className = 'char-count warning';
    } else if (len > 500) {
        charCounter.className = 'char-count error';
    } else {
        charCounter.className = 'char-count';
    }

    // Enable/disable send button
    chatSendBtn.disabled = len === 0 || len > 500;
});

// Logout button
logoutBtn.addEventListener('click', function () {
    isIntentionalDisconnect = true;
    storedIdToken = '';
    currentRoomName = '';
    hideReconnectBanner();
    if (reconnectTimeoutId) {
        clearTimeout(reconnectTimeoutId);
        reconnectTimeoutId = null;
    }
    if (socket) {
        socket.close(); // Triggers socket.onclose to reset state
    }
});

// Switch Room button
switchRoomBtn.addEventListener('click', function () {
    switchRoom();
});

// Sidebar drawer toggle on mobile
sidebarToggle.addEventListener('click', function (e) {
    e.stopPropagation();
    sidebar.classList.toggle('sidebar-open');
});

// Dismiss sidebar if clicking in the messages area
chatMessages.addEventListener('click', function () {
    if (sidebar.classList.contains('sidebar-open')) {
        sidebar.classList.remove('sidebar-open');
    }
});
