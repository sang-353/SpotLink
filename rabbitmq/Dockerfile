FROM rabbitmq:3.9.29-management

# 下载并安装延迟消息插件（支持 x-delayed-message 交换机类型）
ADD https://github.com/rabbitmq/rabbitmq-delayed-message-exchange/releases/download/3.9.0/rabbitmq_delayed_message_exchange-3.9.0.ez /opt/rabbitmq/plugins/

# --offline: 容器未启动时离线启用，避免运行时 rabbitmq-plugins 报错
RUN rabbitmq-plugins enable --offline rabbitmq_delayed_message_exchange
