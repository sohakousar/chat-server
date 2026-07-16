// Configuration and State
const SERVER_URL = 'ws://localhost:8887';
let socket = null;
let myUsername = '';
let myEmail = '';
let isAdmin = false;

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
let rateLimitTimeoutId = null;
let rateLimitHideTimeoutId = null;

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

function handleAuthSuccess(displayName, email, msgIsAdmin) {
    console.log("AUTH_SUCCESS received");
    myUsername = displayName;
    myEmail = email || displayName;
    isAdmin = msgIsAdmin || false;
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
    const mockCounts = {
        'general': 12,
        'random': 7,
        'tech-talk': 5,
        'gaming': 9
    };
    rooms.forEach(room => {
        const btn = document.createElement('div');
        btn.className = 'room-card';
        const count = mockCounts[room.toLowerCase()] || 3;
        btn.innerHTML = `
            <div class="room-card-header">
                <span class="room-card-pulse status-indicator-dot online"></span>
                <span class="room-card-hash">#</span>
                <h4 class="room-card-title">${escapeHTML(room)}</h4>
            </div>
            <div class="room-card-footer">
                <span class="room-card-members">${count} transmitting</span>
            </div>
        `;
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

function showRateLimitWarning(text) {
    const banner = document.getElementById('rate-limit-banner');
    const bannerText = document.getElementById('rate-limit-text');
    if (!banner || !bannerText) return;

    bannerText.textContent = text;
    banner.classList.remove('hidden');
    banner.style.opacity = '1';

    // Clear any pending timeouts
    if (rateLimitTimeoutId) {
        clearTimeout(rateLimitTimeoutId);
        rateLimitTimeoutId = null;
    }
    if (rateLimitHideTimeoutId) {
        clearTimeout(rateLimitHideTimeoutId);
        rateLimitHideTimeoutId = null;
    }

    rateLimitTimeoutId = setTimeout(() => {
        banner.style.opacity = '0';
        rateLimitHideTimeoutId = setTimeout(() => {
            banner.classList.add('hidden');
            rateLimitHideTimeoutId = null;
        }, 250);
        rateLimitTimeoutId = null;
    }, 3500);
}

function updateReactionsUI(wrapper, reactions) {
    let reactionBar = wrapper.querySelector('.reaction-bar');
    if (!reactionBar) {
        reactionBar = document.createElement('div');
        reactionBar.className = 'reaction-bar';

        const pillsContainer = document.createElement('div');
        pillsContainer.className = 'reaction-pills';
        reactionBar.appendChild(pillsContainer);

        const addContainer = document.createElement('div');
        addContainer.className = 'add-reaction-container';

        const addBtn = document.createElement('button');
        addBtn.className = 'add-reaction-btn';
        addBtn.title = 'Add reaction';
        addBtn.innerHTML = `<svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="lucide lucide-smile-plus"><path d="M22 11V9a2 2 0 0 0-2-2h-3"/><path d="M11 22a9 9 0 1 1 9-9"/><path d="M8 14s1.5 2 3.5 2 3.5-2 3.5-2"/><line x1="9" x2="9.01" y1="9" y2="9"/><line x1="15" x2="15.01" y1="9" y2="9"/><line x1="19" x2="19" y1="16" y2="22"/><line x1="16" x2="22" y1="19" y2="19"/></svg>`;

        const picker = document.createElement('div');
        picker.className = 'emoji-picker hidden';

        const commonEmojis = ['👍', '❤️', '😂', '😮', '😢', '🎉'];
        commonEmojis.forEach(emoji => {
            const emojiBtn = document.createElement('button');
            emojiBtn.className = 'emoji-picker-btn';
            emojiBtn.textContent = emoji;
            emojiBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                toggleEmojiReaction(parseInt(wrapper.dataset.messageId), emoji, wrapper.reactionsList);
                picker.classList.add('hidden');
            });
            picker.appendChild(emojiBtn);
        });

        addBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            picker.classList.toggle('hidden');
        });

        document.addEventListener('click', () => {
            picker.classList.add('hidden');
        });

        addContainer.appendChild(addBtn);
        addContainer.appendChild(picker);
        reactionBar.appendChild(addContainer);

        wrapper.appendChild(reactionBar);
    }

    const pillsContainer = reactionBar.querySelector('.reaction-pills');
    pillsContainer.innerHTML = '';

    const grouped = {};
    if (reactions) {
        reactions.forEach(r => {
            if (!grouped[r.emoji]) {
                grouped[r.emoji] = {
                    emoji: r.emoji,
                    count: 0,
                    users: []
                };
            }
            grouped[r.emoji].count++;
            grouped[r.emoji].users.push(r.username);
        });
    }

    Object.values(grouped).forEach(group => {
        const pill = document.createElement('button');
        const userHasReacted = group.users.some(u => u.toLowerCase() === myEmail.toLowerCase());
        pill.className = `reaction-pill ${userHasReacted ? 'reacted' : ''}`;
        pill.innerHTML = `${group.emoji} <span class="reaction-count">${group.count}</span>`;
        pill.title = group.users.join(', ');

        pill.addEventListener('click', (e) => {
            e.stopPropagation();
            toggleEmojiReaction(parseInt(wrapper.dataset.messageId), group.emoji, wrapper.reactionsList);
        });

        pillsContainer.appendChild(pill);
    });
}

function toggleEmojiReaction(messageId, emoji, currentReactions) {
    if (!socket || socket.readyState !== WebSocket.OPEN) {
        return;
    }

    const alreadyReacted = currentReactions && currentReactions.some(
        r => r.emoji === emoji && r.username.toLowerCase() === myEmail.toLowerCase()
    );

    const type = alreadyReacted ? 'REMOVE_REACTION' : 'ADD_REACTION';
    const msg = {
        messageId: messageId,
        emoji: emoji,
        timestamp: new Date().toISOString(),
        type: type
    };
    socket.send(JSON.stringify(msg));
}

function showRateLimitWarning(text) {
    const banner = document.getElementById('rate-limit-banner');
    const bannerText = document.getElementById('rate-limit-text');
    if (!banner || !bannerText) return;

    bannerText.textContent = text;
    banner.classList.remove('hidden');
    banner.style.opacity = '1';

    // Clear any pending timeouts
    if (rateLimitTimeoutId) {
        clearTimeout(rateLimitTimeoutId);
        rateLimitTimeoutId = null;
    }
    if (rateLimitHideTimeoutId) {
        clearTimeout(rateLimitHideTimeoutId);
        rateLimitHideTimeoutId = null;
    }

    rateLimitTimeoutId = setTimeout(() => {
        banner.style.opacity = '0';
        rateLimitHideTimeoutId = setTimeout(() => {
            banner.classList.add('hidden');
            rateLimitHideTimeoutId = null;
        }, 250);
        rateLimitTimeoutId = null;
    }, 3500);
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
                    const email = msg.token || displayName;
                    const msgIsAdmin = msg.isAdmin || false;
                    handleAuthSuccess(displayName, email, msgIsAdmin);
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
    } else if (msg.type === 'RATE_LIMITED') {
        showRateLimitWarning(msg.text);
    } else if (msg.type === 'REACTION_UPDATE') {
        handleReactionUpdate(msg);
    } else if (msg.type === 'MESSAGE_EDITED') {
        const wrapper = document.querySelector(`.message-wrapper[data-message-id="${msg.messageId}"]`);
        if (wrapper) {
            const textSpan = wrapper.querySelector('.message-text');
            if (textSpan) {
                textSpan.textContent = msg.newText || msg.text;
            }
            const timestampSpan = wrapper.querySelector('.message-timestamp');
            if (timestampSpan && !timestampSpan.querySelector('.message-edited')) {
                const editedSpan = document.createElement('span');
                editedSpan.className = 'message-edited';
                editedSpan.textContent = ' (edited)';
                timestampSpan.appendChild(editedSpan);
            }
        }
    } else if (msg.type === 'MESSAGE_DELETED') {
        console.log("MESSAGE_DELETED received for messageId:", msg.messageId);
        const wrapper = document.querySelector(`.message-wrapper[data-message-id="${msg.messageId}"]`);
        if (wrapper) {
            const bubble = wrapper.querySelector('.message-bubble');
            if (bubble) {
                bubble.className = 'message-bubble message-deleted';
                bubble.innerHTML = `
                    <span class="message-text italic muted">This message was deleted</span>
                    <span class="message-timestamp">${formatTime(msg.timestamp)}</span>
                `;
            }
            const reactionBar = wrapper.querySelector('.reaction-bar');
            if (reactionBar) {
                reactionBar.remove();
            }
        } else {
            console.warn("Could not find message element for id:", msg.messageId);
        }
    } else if (msg.type === 'KICKED') {
        handleKickedMessage(msg.text);
    }
}

function handleKickedMessage(reason) {
    isIntentionalDisconnect = true;
    if (socket) {
        socket.close();
    }
    chatApp.innerHTML = `
        <div style="display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100vh; background-color: var(--bg-main); color: var(--text-primary); text-align: center; padding: 2rem;">
            <div style="font-size: 4rem; margin-bottom: 1.5rem;">🚫</div>
            <h2 style="font-weight: 600; margin-bottom: 1rem; color: #ef4444;">Removed from Room</h2>
            <p style="color: var(--text-secondary); max-width: 400px; margin-bottom: 2rem; line-height: 1.6;">${escapeHTML(reason || "You have been removed from the room by an admin.")}</p>
            <button id="kicked-ok-btn" class="login-submit-btn" style="width: auto; padding: 0.75rem 2rem;">Return to Login</button>
        </div>
    `;
    chatApp.classList.remove('hidden');
    loginOverlay.classList.add('hidden');
    roomOverlay.classList.add('hidden');
    
    document.getElementById('kicked-ok-btn').addEventListener('click', () => {
        window.location.reload();
    });
}

function handleReactionUpdate(msg) {
    const wrapper = document.querySelector(`.message-wrapper[data-message-id="${msg.messageId}"]`);
    if (!wrapper) return;

    let list = wrapper.reactionsList || [];
    if (msg.action === 'added') {
        const alreadyExists = list.some(r => r.emoji === msg.emoji && r.username.toLowerCase() === msg.username.toLowerCase());
        if (!alreadyExists) {
            list.push({ emoji: msg.emoji, username: msg.username });
        }
    } else if (msg.action === 'removed') {
        list = list.filter(r => !(r.emoji === msg.emoji && r.username.toLowerCase() === msg.username.toLowerCase()));
    }
    wrapper.reactionsList = list;
    updateReactionsUI(wrapper, list);
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
    const isSelf = msg.sender.toLowerCase() === myUsername.toLowerCase() || msg.sender.toLowerCase() === myEmail.toLowerCase();

    const wrapper = document.createElement('div');
    wrapper.className = `message-wrapper ${isSelf ? 'self' : 'other'}`;
    if (msg.id) {
        wrapper.dataset.messageId = msg.id;
    }

    let metaHTML = '';
    if (!isSelf) {
        // Show sender's name above message bubbles from other users
        metaHTML = `<div class="message-meta">${escapeHTML(msg.sender)}</div>`;
    }

    const formattedTime = formatTime(msg.timestamp);

    if (msg.deleted) {
        wrapper.innerHTML = `
            ${metaHTML}
            <div class="message-bubble message-deleted">
                <span class="message-text italic muted">This message was deleted</span>
                <span class="message-timestamp">${formattedTime}</span>
            </div>
        `;
        chatMessages.appendChild(wrapper);
        scrollToBottom();
        return;
    }

    const escapedText = escapeHTML(msg.text);
    const editedTag = msg.editedAt ? ' <span class="message-edited">(edited)</span>' : '';

    let actionsHTML = '';
    if (isSelf && msg.id) {
        actionsHTML = `
            <div class="message-actions">
                <button class="message-action-btn edit-btn" title="Edit text">
                    <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="lucide lucide-pencil"><path d="M12 20h9"/><path d="M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4Z"/></svg>
                </button>
                <button class="message-action-btn delete-btn" title="Delete message">
                    <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="lucide lucide-trash-2"><path d="M3 6h18"/><path d="M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6"/><path d="M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2"/><line x1="10" x2="10" y1="11" y2="17"/><line x1="14" x2="14" y1="11" y2="17"/></svg>
                </button>
            </div>
        `;
    }

    wrapper.innerHTML = `
        ${metaHTML}
        <div class="message-bubble">
            <span class="message-text">${escapedText}</span>
            <span class="message-timestamp">${formattedTime}${editedTag}</span>
            ${actionsHTML}
        </div>
    `;

    chatMessages.appendChild(wrapper);

    if (msg.id) {
        wrapper.reactionsList = msg.reactions || [];
        updateReactionsUI(wrapper, wrapper.reactionsList);

        if (isSelf) {
            const editBtn = wrapper.querySelector('.edit-btn');
            const deleteBtn = wrapper.querySelector('.delete-btn');
            if (editBtn) {
                editBtn.addEventListener('click', (e) => {
                    e.stopPropagation();
                    console.log("Edit icon clicked for message: " + msg.id);
                    enterEditMode(wrapper, msg.id);
                });
            }
            if (deleteBtn) {
                deleteBtn.addEventListener('click', (e) => {
                    e.stopPropagation();
                    console.log("Delete icon clicked for message: " + msg.id);
                    enterDeleteMode(wrapper, msg.id);
                });
            }
        }
    }

    scrollToBottom();
}

function enterEditMode(wrapper, messageId) {
    const bubble = wrapper.querySelector('.message-bubble');
    const textSpan = bubble.querySelector('.message-text');
    const actions = bubble.querySelector('.message-actions');
    
    if (bubble.querySelector('.edit-input-container')) return;
    
    const originalText = textSpan.innerText;
    textSpan.classList.add('hidden');
    if (actions) actions.classList.add('hidden');
    
    const editContainer = document.createElement('div');
    editContainer.className = 'edit-input-container';
    
    const input = document.createElement('input');
    input.type = 'text';
    input.className = 'message-edit-input';
    input.value = originalText;
    
    const buttonsDiv = document.createElement('div');
    buttonsDiv.className = 'edit-input-container-buttons';
    
    const saveBtn = document.createElement('button');
    saveBtn.className = 'edit-save-btn';
    saveBtn.textContent = 'Save';
    
    const cancelBtn = document.createElement('button');
    cancelBtn.className = 'edit-cancel-btn';
    cancelBtn.textContent = 'Cancel';
    
    buttonsDiv.appendChild(saveBtn);
    buttonsDiv.appendChild(cancelBtn);
    editContainer.appendChild(input);
    editContainer.appendChild(buttonsDiv);
    bubble.appendChild(editContainer);
    
    input.focus();
    input.select();
    
    const exitEdit = () => {
        editContainer.remove();
        textSpan.classList.remove('hidden');
        if (actions) actions.classList.remove('hidden');
    };
    
    const saveEdit = () => {
        const newText = input.value.trim();
        if (newText !== '' && newText !== originalText) {
            const editMsg = {
                messageId: messageId,
                text: newText,
                newText: newText,
                timestamp: new Date().toISOString(),
                type: 'EDIT_MESSAGE'
            };
            console.log("Sending EDIT_MESSAGE for messageId:", messageId, typeof messageId);
            if (socket) {
                console.log("Socket readyState:", socket.readyState);
            } else {
                console.log("Socket is null!");
            }
            try {
                socket.send(JSON.stringify(editMsg));
                console.log("EDIT_MESSAGE successfully sent via socket.send");
            } catch (err) {
                console.error("Error sending EDIT_MESSAGE:", err);
            }
        }
        exitEdit();
    };
    
    input.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
            e.preventDefault();
            saveEdit();
        } else if (e.key === 'Escape') {
            e.preventDefault();
            exitEdit();
        }
    });
    
    saveBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        saveEdit();
    });
    
    cancelBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        exitEdit();
    });
}

function enterDeleteMode(wrapper, messageId) {
    const bubble = wrapper.querySelector('.message-bubble');
    const actions = bubble.querySelector('.message-actions');
    
    if (bubble.querySelector('.delete-confirm-container')) return;
    
    if (actions) actions.classList.add('hidden');
    
    const confirmContainer = document.createElement('div');
    confirmContainer.className = 'delete-confirm-container';
    
    const label = document.createElement('span');
    label.className = 'delete-confirm-label';
    label.textContent = 'Delete this message? ';
    
    const yesBtn = document.createElement('button');
    yesBtn.className = 'delete-confirm-yes';
    yesBtn.textContent = 'Yes';
    
    const noBtn = document.createElement('button');
    noBtn.className = 'delete-confirm-no';
    noBtn.textContent = 'No';
    
    confirmContainer.appendChild(label);
    confirmContainer.appendChild(yesBtn);
    confirmContainer.appendChild(noBtn);
    bubble.appendChild(confirmContainer);
    
    const exitDelete = () => {
        confirmContainer.remove();
        if (actions) actions.classList.remove('hidden');
    };
    
    yesBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        console.log("Delete confirmed (Yes clicked) for message: " + messageId);
        const deleteMsg = {
            messageId: messageId,
            timestamp: new Date().toISOString(),
            type: 'DELETE_MESSAGE'
        };
        console.log("Sending DELETE_MESSAGE for messageId:", messageId, typeof messageId);
        if (socket) {
            console.log("Socket readyState:", socket.readyState);
        } else {
            console.log("Socket is null!");
        }
        try {
            socket.send(JSON.stringify(deleteMsg));
            console.log("DELETE_MESSAGE successfully sent via socket.send");
        } catch (err) {
            console.error("Error sending DELETE_MESSAGE:", err);
        }
        exitDelete();
    });
    
    noBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        console.log("Delete cancelled (No clicked) for message: " + messageId);
        exitDelete();
    });
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
    users.sort((a, b) => {
        const nameA = a.split('|')[0];
        const nameB = b.split('|')[0];
        return nameA.localeCompare(nameB);
    });

    users.forEach(userStr => {
        const parts = userStr.split('|');
        const dispName = parts[0];
        const email = parts[1] || dispName;

        const li = document.createElement('li');
        const isMe = email.toLowerCase() === myEmail.toLowerCase();
        const isTargetAdmin = email.toLowerCase() === 'syeda.soha.kousar@gmail.com';

        let kickButtonHTML = '';
        if (isAdmin && !isMe && !isTargetAdmin) {
            kickButtonHTML = `
                <button class="kick-btn" title="Kick user">
                    <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="lucide lucide-user-x"><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><line x1="17" x2="22" y1="8" y2="13"/><line x1="22" x2="17" y1="8" y2="13"/></svg>
                </button>
            `;
        }

        li.innerHTML = `
            <span class="status-indicator-dot online signal-pulse-dot" style="margin-right: 8px;"></span>
            <span class="user-name-span">${escapeHTML(dispName)} ${isMe ? '<small style="color: var(--text-muted); font-size: 0.75rem;">(You)</small>' : ''}</span>
            ${kickButtonHTML}
        `;
        onlineUsersList.appendChild(li);

        if (isAdmin && !isMe && !isTargetAdmin) {
            const kickBtn = li.querySelector('.kick-btn');
            if (kickBtn) {
                kickBtn.addEventListener('click', (e) => {
                    e.stopPropagation();
                    showKickConfirmation(li, dispName, email);
                });
            }
        }
    });
}

function showKickConfirmation(li, dispName, email) {
    if (li.querySelector('.kick-confirm-container')) return;

    const nameSpan = li.querySelector('.user-name-span');
    const kickBtn = li.querySelector('.kick-btn');
    if (nameSpan) nameSpan.classList.add('hidden');
    if (kickBtn) kickBtn.classList.add('hidden');

    const confirmContainer = document.createElement('div');
    confirmContainer.className = 'kick-confirm-container';

    const label = document.createElement('span');
    label.className = 'kick-confirm-label';
    label.textContent = `Kick ${dispName}? `;

    const yesBtn = document.createElement('button');
    yesBtn.className = 'kick-confirm-yes';
    yesBtn.textContent = 'Yes';

    const noBtn = document.createElement('button');
    noBtn.className = 'kick-confirm-no';
    noBtn.textContent = 'No';

    confirmContainer.appendChild(label);
    confirmContainer.appendChild(yesBtn);
    confirmContainer.appendChild(noBtn);
    li.appendChild(confirmContainer);

    const exitConfirm = () => {
        confirmContainer.remove();
        if (nameSpan) nameSpan.classList.remove('hidden');
        if (kickBtn) kickBtn.classList.remove('hidden');
    };

    yesBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        console.log("Kicking user: " + email);
        const kickMsg = {
            targetEmail: email,
            timestamp: new Date().toISOString(),
            type: 'KICK_USER'
        };
        socket.send(JSON.stringify(kickMsg));
        exitConfirm();
    });

    noBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        exitConfirm();
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
