#!/bin/bash
mvn clean package
cp /root/maven/target/keycloak-telegram-auth-1.0.0.jar /home/keycloack/plugins/keycloak-telegram-auth-1.0.0.jar
# Переменные
COMPOSE_DIR="/home/keycloack"
SERVICE_NAME="keycloak_web"

# Переходим в директорию с docker-compose
cd "$COMPOSE_DIR" || { echo "Ошибка: не удалось перейти в директорию $COMPOSE_DIR"; exit 1; }

# Останавливаем сервисы
echo "Останавливаем сервисы..."
docker compose stop || { echo "Ошибка при остановке сервисов"; exit 1; }

# Получаем ID образа для указанного сервиса
IMAGE_ID=$(docker compose images -q "$SERVICE_NAME")

if [ -z "$IMAGE_ID" ]; then
    echo "Не удалось найти ID образа для сервиса $SERVICE_NAME"
else
    echo "Удаляем образ $IMAGE_ID..."
    docker rmi -f "$IMAGE_ID" || { echo "Ошибка при удалении образа"; exit 1; }
fi

# Пересоздаем и запускаем сервисы
echo "Пересоздаем и запускаем сервисы..."
docker compose up -d --force-recreate || { echo "Ошибка при пересоздании сервисов"; exit 1; }

echo "Операция завершена успешно"
