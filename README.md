# Ymshop

一个面向 Spigot / Folia 的商店插件，GUI 基于 `easygui-bundle-1.0-SNAPSHOT.jar`。

## 默认结构

当前默认会释放一套分类结构：

- `shops/main.yml`：商店首页
- `shops/crops.yml`：农作物商店
- `shops/minerals.yml`：矿物商店
- `shops/food.yml`：食物商店
- `shops/blocks.yml`：方块商店
- `shops/misc.yml`：杂项商店

首页布局和分类商店布局：

- `layouts/main-menu.yml`
- `layouts/category-shop-menu.yml`

你后续新增、删除、修改商品，只需要编辑：

- `plugins/Ymshop/shops/*.yml`

你后续新增、删除、修改布局，只需要编辑：

- `plugins/Ymshop/layouts/*.yml`

## 当前交互

同一个商品按钮同时支持购买和出售：

- 左键：购买 1 个
- Shift + 左键：购买 64 个
- 右键：出售 1 个
- Shift + 右键：出售全部

点击商品后会先进入确认菜单，再真正执行交易。

如果存在 `main` 商店，玩家直接输入：

- `/ymshop`

会直接打开首页。

## 首页和分类

`main.yml` 只负责分类跳转，默认提供五个分类入口：

- 农作物
- 矿物
- 食物
- 方块
- 杂项

你可以继续自己新增更多分类商店，然后在 `main-menu.yml` 里加按钮跳转。

## 商店文件说明

每个 `shops/*.yml` 都是一个独立商店。

最小结构：

```yaml
settings:
  menu: category-shop-menu
  buy-more: true
  shop-name: "&6你的商店"
  hide-message: false

items:
  example_item:
    display-name: "&e示例物品"
    currency: vault
    buy-price: 100
    sell-price: 50
    products:
      1:
        material: DIAMOND
        amount: 1
```

说明：

- `settings.menu` 必须指向一个已存在的布局文件名，不带 `.yml`
- `items` 下面每个节点就是一个商品
- `items` 的 key 只是商品 id，不需要和布局字符对应
- 你直接增删 `items` 节点即可完成商品增删
- 商品会按配置顺序自动填充到布局的商品槽位

## 布局文件说明

每个 `layouts/*.yml` 都是一个独立布局。

规则：

- `layout` 里所有没有在 `buttons` 里定义的字符，都会被当成商品槽位
- `buttons` 里定义的是功能按钮，例如关闭、上一页、下一页、返回首页
- 已支持按钮动作：`close`、`open_menu` / `open_shop`、`back`、`reload`、`command`、`previous_page`、`next_page`

## 分页

如果商品数量超过布局里的商品槽位数量，会自动分页。

默认分类布局已经带：

- `3`：返回首页
- `4`：上一页
- `5`：下一页

## 货币

当前默认配置的是：

```yaml
currencies:
  vault:
    type: VAULT
    display-name: "&6金币"
```

商店里推荐直接写：

```yaml
currency: vault
```

为了兼容旧配置，`coins` 也仍然可用，但默认模板已经统一改成 `vault`。

使用 `vault` 需要：

- 安装 `Vault`
- 安装一个真正提供经济服务的经济插件

否则会提示找不到 `Vault` 经济实现。

## 条件 Lore

商品 lore 现在会按状态动态显示，不是所有提示都常驻。

已经支持的条件提示：

- 玩家购买限制
- 服务器购买限制
- 玩家出售限制
- 服务器出售限制
- 无法再购买更多
- 已售罄
- 余额不足
- 无法再出售更多
- 背包里没有可出售的物品

没触发时对应行会自动隐藏。

## 时间重置

支持懒加载时间重置。玩家打开商店或交易时，会自动检查并重置对应买卖计数。

当前支持：

- `TIMED`：每日固定时间重置
- `WEEKLY`：每周固定日期 + 时间重置
- `INTERVAL`：固定间隔重置

示例：

```yaml
buy-times-reset-mode: TIMED
buy-times-reset-time: "00:00:00"

sell-times-reset-mode: WEEKLY
sell-times-reset-day: MONDAY
sell-times-reset-time: "06:00:00"

buy-times-reset-mode: INTERVAL
buy-times-reset-interval: "12h"
```

间隔支持：

- `30s`
- `15m`
- `12h`
- `1d`
- `1w`

## 交易日志

每次成功购买或出售，都会写入日志文件：

- `plugins/Ymshop/logs/trade-YYYY-MM-DD.log`

日志内容包含：

- 时间
- 玩家
- UUID
- 交易方向
- 商店
- 商品
- 数量
- 单价
- 总价
- 货币

适合查账、排查刷钱和回滚。

## 颜色

支持普通颜色代码：

- `&a`
- `&6`

支持 16 进制颜色两种写法：

- `&#FF7777`
- `&x&F&F&7&7&7&7`

## 命令

- `/ymshop`：如果存在 `main` 商店则直接打开首页
- `/ymshop reload`：重载配置
- `/ymshop open <shop> [player]`：打开指定商店
- `/ymshop itemmodel`：读取主手物品的 `item-model` 配置片段

## 配置报错

现在配置报错会尽量直接指出具体文件和节点，例如：

- `shops/crops.yml -> items.wheat -> missing currency`
- `layouts/main-menu.yml -> buttons.A -> display-item missing`

这样改配置时更容易定位问题。

## 重要说明

插件只会在第一次启动时释放默认配置文件。

如果你已经开过服，后来又更新了 jar，那么：

- `plugins/Ymshop/config.yml`
- `plugins/Ymshop/shops/*.yml`
- `plugins/Ymshop/layouts/*.yml`

不会自动被新 jar 覆盖，你需要自己手动同步或删除旧文件后重新生成。
