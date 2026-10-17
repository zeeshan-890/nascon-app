module.exports = {
  rootDir: '..',
  roots: ['<rootDir>/tests'],
  testEnvironment: 'node',
  setupFiles: ['<rootDir>/tests/setup.js'],
  globalSetup: '<rootDir>/tests/globalSetup.js',
  modulePaths: ['<rootDir>/backend/node_modules'],
  collectCoverageFrom: ['backend/src/domain/**/*.js'],
  coverageDirectory: '<rootDir>/backend/coverage',
  testTimeout: 30000,
  maxWorkers: 1,
  forceExit: true,
};
