// Tests always use a fixed salt, regardless of any DAY1_SALT exported in the shell.
process.env.DAY1_SALT = 'demo-salt';
process.env.NODE_ENV = 'test';
// Public reference salt used only for the Part II signing test vectors.
process.env.DAY2_SALT = 'T01D27ad7ffff12e';
