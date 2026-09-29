# 資料檔

第一次啟動時會複製到 `plugins/ExileCore/data/`，之後改那裡的檔案再 `/ec reload` 即可；
jar 裡的這份只是預設值（已存在的檔案不會被覆蓋）。

| 檔案 | 內容 |
| --- | --- |
| items.yml | 物品等級縮放與品質加成 |
| names.yml | 稀有物品／稀有怪的隨機名字 |
| currencies.yml | 通貨定義 |
| monsters.yml | 怪物等級、生命／傷害表、稀有度、原版生物對照、怪物詞綴 |
| drops.yml | 掉落表 |
| bases/*.yml | 基底物品（武器、防具、盾、飾品） |
| affixes/*.yml | 前綴與後綴 |

設定檔寫錯時啟動會失敗並在主控台指出檔名與欄位。
