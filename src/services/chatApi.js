export async function sendChatMessage(question, options = {}) {
  await new Promise((resolve) => setTimeout(resolve, 600))

  return {
    answer: `我已收到您的问题：“${question}”。后续这里会接入 Dify 智能体接口。`,
    conversationId: options.conversationId || 'mock-conversation-001',
  }
}
