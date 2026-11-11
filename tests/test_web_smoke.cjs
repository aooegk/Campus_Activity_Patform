const assert = require('node:assert/strict')
const { chromium } = require(process.env.CAMPUS_PLAYWRIGHT || 'playwright-core')
const base = process.env.BASE_URL || 'http://127.0.0.1:8080'

async function main() {
  const browser = await chromium.launch({ executablePath: process.env.CAMPUS_BROWSER, headless: true })
  try {
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 } })
    const page = await context.newPage()
    page.setDefaultTimeout(10000)
    const errors = []
    page.on('pageerror', error => errors.push(error.message))
    page.on('console', message => {
      if (message.type() === 'error' && /^(Reference|Type|Syntax)Error:/.test(message.text())) errors.push(message.text())
    })
    await page.goto(base + '/')
    await page.locator('#login-account').fill('organizer')
    await page.locator('#login-password').fill('123456')
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await page.getByRole('button', { name: '活动大厅', exact: true }).waitFor()
    assert.equal(await page.evaluate(() => localStorage.getItem('token')), null)
    assert.ok((await context.cookies()).some(cookie => cookie.name === 'campus_session' && cookie.httpOnly))

    await page.getByRole('heading', { name: /羽毛球双打/ }).click()
    await page.getByRole('button', { name: '修改活动', exact: true }).click()
    await page.getByRole('heading', { name: '修改活动', exact: true }).waitFor()
    await page.getByRole('button', { name: '取消', exact: true }).click()
    await page.getByRole('button', { name: '查看报名名单', exact: true }).click()
    await page.getByRole('heading', { name: /正式名单（/ }).waitFor()

    if (process.env.CAMPUS_VERIFY_FEEDBACK === '1') {
      await page.getByRole('button', { name: '我的活动', exact: true }).click()
      await page.getByRole('button', { name: '历史', exact: true }).click()
      const fixture = page.locator('.card').filter({
        has: page.getByRole('heading', { name: '活动签到验收', exact: true }),
      })
      await fixture.getByRole('button', { name: '查看活动', exact: true }).click()
      await page.getByRole('button', { name: '查看签到与出勤', exact: true }).click()
      await page.getByRole('heading', { name: '正式成员出勤', exact: true }).waitFor()
      await page.getByRole('button', { name: '查看评价', exact: true }).click()
      await page.getByText('场地可以再宽敞一些', { exact: false }).waitFor()
    }

    await page.getByRole('button', { name: '退出', exact: true }).click()
    await page.locator('#login-account').waitFor()
    assert.ok(!(await context.cookies()).some(cookie => cookie.name === 'campus_session'))
    assert.deepEqual(errors, [])
    console.log('PASS - browser cookie login, catalogue, editing, roster and logout')
  } finally {
    await browser.close()
  }
}

main().catch(error => {
  console.error(error)
  process.exitCode = 1
})
