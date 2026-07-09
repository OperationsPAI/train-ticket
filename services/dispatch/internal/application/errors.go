package application

type Error struct{ Code, Message string }

func (e Error) Error() string             { return e.Message }
func NewError(code, message string) Error { return Error{Code: code, Message: message} }
