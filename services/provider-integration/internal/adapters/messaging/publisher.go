package messaging

import (
	kitmsg "github.com/trainticket/greenfield/platform/go-kit/messaging"
)

type RedisPublisher = kitmsg.RedisEventBus

func NewRedisPublisher(redisURL string) (*RedisPublisher, error) {
	return kitmsg.NewRedisEventBus(redisURL)
}
