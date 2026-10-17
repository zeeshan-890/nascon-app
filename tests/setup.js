// Tests always use a fixed salt, regardless of any DAY1_SALT exported in the shell.
process.env.DAY1_SALT = 'demo-salt';
process.env.NODE_ENV = 'test';
