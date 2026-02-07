class ChatApp {
    constructor() {
        this.elements = {
            chatForm: document.getElementById('chatForm'),
            userInput: document.getElementById('userInput'),
            chatMessages: document.getElementById('chatMessages'),
            sendButton: document.getElementById('sendButton'),
            ingestBtn: document.getElementById('ingestBtn'),
            ingestIcon: document.getElementById('ingestIcon'),
            ingestText: document.getElementById('ingestText'),
            indexStatus: document.getElementById('indexStatus'),
        };

        this.conversationHistory = [];
        this.isLoading = false;

        this.init();
    }

    init() {
        this.bindEvents();
        this.checkIndexStatus();
        this.autoResizeTextarea();
    }

    bindEvents() {
        this.elements.chatForm.addEventListener('submit', (e) => this.handleSubmit(e));

        this.elements.userInput.addEventListener('keydown', (e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                this.elements.chatForm.dispatchEvent(new Event('submit'));
            }
        });

        this.elements.userInput.addEventListener('input', () => this.autoResizeTextarea());

        this.elements.ingestBtn.addEventListener('click', () => this.handleIngest());
    }

    autoResizeTextarea() {
        const textarea = this.elements.userInput;
        textarea.style.height = 'auto';
        textarea.style.height = Math.min(textarea.scrollHeight, 150) + 'px';
    }

    async handleSubmit(e) {
        e.preventDefault();

        const message = this.elements.userInput.value.trim();
        if (!message || this.isLoading) return;

        this.elements.userInput.value = '';
        this.autoResizeTextarea();

        this.addMessage(message, 'user');

        this.setLoading(true);
        const typingIndicator = this.addTypingIndicator();

        try {
            const response = await this.sendMessage(message);
            typingIndicator.remove();

            if (response.success) {
                this.addMessage(response.message, 'assistant', response.sources || []);

                this.conversationHistory.push(
                    { role: 'user', content: message },
                    { role: 'assistant', content: response.message }
                );

                if (this.conversationHistory.length > 20) {
                    this.conversationHistory = this.conversationHistory.slice(-20);
                }
            } else {
                this.addErrorMessage(response.error || 'An error occurred. Please try again.');
            }
        } catch (error) {
            typingIndicator.remove();
            this.addErrorMessage('Failed to connect to the server. Please check your connection.');
            console.error('Chat error:', error);
        } finally {
            this.setLoading(false);
        }
    }

    async sendMessage(message) {
        const response = await fetch('/api/chat', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                message,
                history: this.conversationHistory
            })
        });

        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }

        return response.json();
    }

    async handleIngest() {
        this.setIngestLoading(true);
        this.addMessage('Starting documentation indexing... This may take a minute.', 'assistant');

        try {
            const response = await fetch('/api/ingest', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' }
            });

            const data = await response.json();

            if (data.success) {
                this.addMessage(
                    `✅ Indexing complete! Crawled ${data.pagesIndexed} pages and created ${data.chunksCreated} searchable chunks. The assistant is now ready to answer your questions.`,
                    'assistant'
                );
                this.checkIndexStatus();
            } else {
                this.addErrorMessage(`Indexing failed: ${data.error}`);
            }
        } catch (error) {
            this.addErrorMessage(`Failed to index: ${error.message}`);
        } finally {
            this.setIngestLoading(false);
        }
    }

    async checkIndexStatus() {
        try {
            const response = await fetch('/api/index/status');
            const data = await response.json();

            const statusEl = this.elements.indexStatus;

            if (data.indexed) {
                statusEl.textContent = `📚 ${data.documentCount} docs`;
                statusEl.className = 'inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-green-100 text-green-800';
            } else {
                statusEl.textContent = '⚠️ Not indexed';
                statusEl.className = 'inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-yellow-100 text-yellow-800';
            }
        } catch (error) {
            const statusEl = this.elements.indexStatus;
            statusEl.textContent = '❌ Error';
            statusEl.className = 'inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-red-100 text-red-800';
        }
    }

    addMessage(content, role, sources = []) {
        const messageEl = document.createElement('div');
        messageEl.className = `flex gap-3 ${role === 'user' ? 'flex-row-reverse max-w-3xl ml-auto' : 'max-w-3xl'} animate-fade-in`;

        const avatar = this.createAvatar(role);
        const bubble = this.createMessageBubble(content, role, sources);

        messageEl.appendChild(avatar);
        messageEl.appendChild(bubble);

        this.elements.chatMessages.appendChild(messageEl);
        this.scrollToBottom();
    }

    createAvatar(role) {
        const avatar = document.createElement('div');

        if (role === 'user') {
            avatar.className = 'flex-shrink-0 w-8 h-8 bg-primary-600 rounded-full flex items-center justify-center';
            avatar.innerHTML = `
                <svg class="w-5 h-5 text-white" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                    <path d="M20 21V19C20 16.7909 18.2091 15 16 15H8C5.79086 15 4 16.7909 4 19V21" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
                    <circle cx="12" cy="7" r="4" stroke="currentColor" stroke-width="2"/>
                </svg>
            `;
        } else {
            avatar.className = 'flex-shrink-0 w-8 h-8 bg-primary-100 rounded-full flex items-center justify-center';
            avatar.innerHTML = `
                <svg class="w-5 h-5 text-primary-600" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                    <path d="M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 117.072 0l-.548.547A3.374 3.374 0 0014 18.469V19a2 2 0 11-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
            `;
        }

        return avatar;
    }

    createMessageBubble(content, role, sources) {
        const bubble = document.createElement('div');

        if (role === 'user') {
            bubble.className = 'bg-primary-600 text-white rounded-2xl rounded-tr-none p-4';
            bubble.innerHTML = `<p class="text-sm sm:text-base whitespace-pre-wrap">${this.escapeHtml(content)}</p>`;
        } else {
            bubble.className = 'flex-1 bg-gray-50 rounded-2xl rounded-tl-none p-4';

            const formattedContent = this.formatMessage(content, sources);
            let sourcesHtml = '';

            if (sources && sources.length > 0) {
                const uniqueSources = [...new Set(sources)];
                sourcesHtml = `
                    <div class="mt-4 pt-4 border-t border-gray-200">
                        <p class="text-xs font-medium text-gray-500 mb-2">📖 Sources</p>
                        <ol class="space-y-1 list-decimal list-inside">
                            ${uniqueSources.map((url, index) => `
                                <li id="source-${index + 1}" class="text-xs">
                                    <a href="${this.escapeHtml(url)}" target="_blank" rel="noopener noreferrer" 
                                       class="text-primary-600 hover:text-primary-700 hover:underline break-all">
                                        ${this.formatSourceUrl(url)}
                                    </a>
                                </li>
                            `).join('')}
                        </ol>
                    </div>
                `;
            }

            bubble.innerHTML = `
                <div class="prose prose-sm max-w-none text-gray-800">
                    ${formattedContent}
                </div>
                ${sourcesHtml}
            `;
        }

        return bubble;
    }

    formatSourceUrl(url) {
        try {
            const urlObj = new URL(url);
            let display = urlObj.hostname + urlObj.pathname;
            if (display.length > 60) {
                display = display.substring(0, 57) + '...';
            }
            return this.escapeHtml(display);
        } catch {
            return this.escapeHtml(url);
        }
    }

    formatMessage(content, sources = []) {
        if (!content) return '';

        let formatted = this.escapeHtml(content);

        if (sources && sources.length > 0) {
            formatted = formatted.replace(/\[(\d+)\]/g, (match, num) => {
                const index = parseInt(num, 10);
                if (index > 0 && index <= sources.length) {
                    const url = sources[index - 1];
                    const safeUrl = this.escapeHtml(url);
                    return `<a href="${safeUrl}" target="_blank" rel="noopener noreferrer" class="citation-link" title="Source ${num}">${num}</a>`;
                }
                return match;
            });
        }

        formatted = formatted
            .replace(/### (.+)/g, '<h3 class="text-base font-semibold mt-3 mb-2">$1</h3>')
            .replace(/## (.+)/g, '<h2 class="text-lg font-semibold mt-3 mb-2">$1</h2>')
            .replace(/# (.+)/g, '<h1 class="text-xl font-bold mt-3 mb-2">$1</h1>')
            .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
            .replace(/(?<!\*)\*([^*]+)\*(?!\*)/g, '<em>$1</em>')
            .replace(/```([\s\S]*?)```/g, '<pre class="bg-gray-800 text-gray-100 rounded-lg p-3 my-2 overflow-x-auto text-xs"><code>$1</code></pre>')
            .replace(/`([^`]+)`/g, '<code class="bg-gray-200 px-1.5 py-0.5 rounded text-sm font-mono">$1</code>')
            .replace(/\n/g, '<br>');

        return formatted;
    }

    escapeHtml(text) {
        if (!text) return '';
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    addErrorMessage(error) {
        const messageEl = document.createElement('div');
        messageEl.className = 'flex gap-3 max-w-3xl animate-fade-in';

        messageEl.innerHTML = `
            <div class="flex-shrink-0 w-8 h-8 bg-red-100 rounded-full flex items-center justify-center">
                <svg class="w-5 h-5 text-red-600" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                    <circle cx="12" cy="12" r="10" stroke="currentColor" stroke-width="2"/>
                    <path d="M12 8V12M12 16H12.01" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
                </svg>
            </div>
            <div class="flex-1 bg-red-50 border border-red-200 rounded-2xl rounded-tl-none p-4">
                <p class="text-sm text-red-800">⚠️ ${this.escapeHtml(error)}</p>
            </div>
        `;

        this.elements.chatMessages.appendChild(messageEl);
        this.scrollToBottom();
    }

    addTypingIndicator() {
        const indicator = document.createElement('div');
        indicator.className = 'flex gap-3 max-w-3xl animate-fade-in';
        indicator.id = 'typing-indicator';

        indicator.innerHTML = `
            <div class="flex-shrink-0 w-8 h-8 bg-primary-100 rounded-full flex items-center justify-center">
                <svg class="w-5 h-5 text-primary-600" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                    <path d="M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 117.072 0l-.548.547A3.374 3.374 0 0014 18.469V19a2 2 0 11-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
            </div>
            <div class="bg-gray-50 rounded-2xl rounded-tl-none p-4">
                <div class="flex items-center gap-1">
                    <span class="w-2 h-2 bg-gray-400 rounded-full animate-bounce" style="animation-delay: 0ms;"></span>
                    <span class="w-2 h-2 bg-gray-400 rounded-full animate-bounce" style="animation-delay: 150ms;"></span>
                    <span class="w-2 h-2 bg-gray-400 rounded-full animate-bounce" style="animation-delay: 300ms;"></span>
                </div>
            </div>
        `;

        this.elements.chatMessages.appendChild(indicator);
        this.scrollToBottom();

        return indicator;
    }

    setLoading(loading) {
        this.isLoading = loading;
        this.elements.sendButton.disabled = loading;
        this.elements.userInput.disabled = loading;
    }

    setIngestLoading(loading) {
        this.elements.ingestBtn.disabled = loading;

        if (loading) {
            this.elements.ingestIcon.classList.add('animate-spin');
            this.elements.ingestText.textContent = 'Indexing...';
        } else {
            this.elements.ingestIcon.classList.remove('animate-spin');
            this.elements.ingestText.textContent = 'Rebuild Index';
        }
    }

    scrollToBottom() {
        this.elements.chatMessages.scrollTop = this.elements.chatMessages.scrollHeight;
    }
}

document.addEventListener('DOMContentLoaded', () => {
    new ChatApp();
});

