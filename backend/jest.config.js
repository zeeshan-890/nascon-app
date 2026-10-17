module.exports = {
  rootDir: '..',
  roots: ['<rootDir>/tests'],
  testEnvironment: 'node',
  setupFiles: ['<rootDir>/tests/setup.js'],
  globalSetup: '<rootDir>/tests/globalSetup.js',
  modulePaths: ['<rootDir>/backend/node_modules'],
  collectCoverageFrom: ['backend/src/domain/**/*.js'],
  coverageDirectory: '<rootDir>/backend/coverage',
  coverageThreshold: {
    './backend/src/domain/fixtures/': { lines: 85, statements: 85, functions: 85, branches: 80 },
  },
  testTimeout: 30000,
  maxWorkers: 1,
  forceExit: true,
};
