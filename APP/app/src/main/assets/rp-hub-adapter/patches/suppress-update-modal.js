// 抑制 RP-Hub 更新公告弹窗
// 通过将已读 ID 设置为极大值，使 checkUpdate() 认为公告已读
localStorage.setItem('roleplay_hub_update_id', '999999');
