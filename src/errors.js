'use strict';

class AppError extends Error {
  constructor(message, { status = 400, code = 'BAD_REQUEST', details = null, cause } = {}) {
    super(message, cause ? { cause } : undefined);
    this.name = 'AppError';
    this.status = status;
    this.code = code;
    this.details = details;
  }
}

function isAbortError(error) {
  return error?.name === 'AbortError' || error?.code === 'ABORT_ERR';
}

function publicError(error) {
  if (error instanceof AppError) {
    return {
      status: error.status,
      body: {
        error: {
          code: error.code,
          message: error.message,
          details: error.details,
        },
      },
    };
  }

  console.error(error);
  return {
    status: 500,
    body: {
      error: {
        code: 'INTERNAL_ERROR',
        message: '服务器处理请求时发生未预期错误，请查看运行终端。',
      },
    },
  };
}

module.exports = { AppError, isAbortError, publicError };