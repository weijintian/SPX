using System;
using System.Collections.Generic;
using System.IO;
using System.Text;
using System.Text.RegularExpressions;
using System.Xml;

class RepairStrings {
    static readonly Encoding Gbk = Encoding.GetEncoding(936);
    static readonly Encoding Utf8Strict = new UTF8Encoding(false, true);
    static readonly Regex MapRegex = new Regex("map\\(\\\"([^\\\"]+)\\\", \\\"((?:\\\\.|[^\\\"])*)\\\"\\)");
    static readonly Regex CorruptRegex = new Regex("[\\u59dd\\u93c9\\u93ad\\u7487\\u59c6\\u6d48\\u7f01\\u95ff\\u93ac\\u5b7f\\u9289\\u979a\\u93c8\\u93d6\\u93e8\\u93f5\\u9354\\u95a2\\u6d93\\u6dcb\\u95c7\\u95b4\\u95c8\\u95c9\\u95ca\\u95cb\\u95cc\\u95cd\\u95ce\\u95cf\\u95d0\\u95d1\\u95d2\\u95d3\\u95d4\\u95d5\\u95d6\\u95d7\\u95d8\\u95d9\\u95da\\u95db\\u95dc\\u95dd\\u95de\\u95df\\u95e0\\u95e1\\u95e2\\u95e3\\u95e4\\u95e5\\u95e6\\u95e7\\u95e8\\u95e9\\u95ea\\u95eb\\u95ec\\u95ed\\u95ee\\u95ef\\u95f0\\u95f1\\u95f2\\u95f3\\u95f4\\u95f5\\u95f6\\u95f7\\u95f8\\u95f9\\u95fa\\u95fb\\u95fc\\u95fd\\u95fe\\u95ff}鏈缇闂備杈濂鎴鍒灏鍔緇鍏鐢娓鍙]");
    static readonly Dictionary<string, string> English = new Dictionary<string, string> {
        { "local_login_cleared", "Local sign-in status cleared" }, { "group_invitation_message", "[Group invitation]" },
        { "exchange_usdt_amount_invalid", "Enter a valid USDT amount" }, { "select_friend_first", "Select a friend first" },
        { "group_info_failed", "Failed to load group information: %1$s" }, { "allow_unknown_app_install", "Allow SPX IM to install unknown apps in system settings" },
        { "connecting_update_server", "Connecting to update server..." }, { "check_later", "Check again later" }, { "chat_details", "Chat details" },
        { "referral_reward", "Referral reward" }, { "deposit_fields_required", "Enter the required deposit information" }, { "load_friends_failed", "Failed to load friends" },
        { "staking_records_loading", "Loading staking records..." }, { "group_name_required", "Enter a group name" }, { "load_members_failed", "Failed to load group members" },
        { "ticket_fields_required", "Enter a title and description" }, { "group_info_members", "Group members" }, { "version_info_failed", "Failed to load version information" },
        { "my_benefits", "My Benefits" }, { "profile_load_failed", "Failed to load profile: %1$s" }, { "reading_bound_email", "Loading linked email..." },
        { "exchange_success", "Exchanged successfully. Received %1$s SPX001 points" }, { "invite_friends_to_group", "Invite friends to the group" },
        { "wallet_unbound_hint", "No BSC wallet is linked. Link a wallet to view assets." }, { "binding_confirm", "This address cannot be changed after linking. Confirm it is correct and link permanently?" },
        { "remark_cleared", "Remark cleared" }, { "staking_plans_loading", "Loading platform staking plans..." }, { "details", "Details" },
        { "email_or_spx_id", "Email or SPX ID" }, { "release_history_failed", "Failed to load release history: %1$s" },
        { "deposit_address_loading", "Loading deposit address..." }, { "friends_invited_note", "Invited friends will receive a group invitation." },
        { "invite_to_group", "Invite to group" }, { "friends_loading_or_empty", "Loading friends, or add friends first" },
        { "search_member_hint", "Search members by name, SPX ID, or user ID" }, { "available_balance_loading", "Loading available balance..." },
        { "wallet_balance_loading", "Loading wallet balance..." }, { "apk_verification_failed", "APK verification failed" },
        { "load_friends_failed_detail", "Failed to load friends: %1$s" }, { "staking_amount_integer_required", "Staking amount must be a whole number" },
        { "add_friend_subtitle", "Find friends by email or SPX ID" }, { "agreement_text", "By using SPX IM, you agree to the User Agreement and Privacy Policy. Digital asset features are provided on the BSC network." },
        { "reading_group_info", "Loading group information..." }, { "block_confirm", "Block this user? You will no longer receive their messages." },
        { "allow_member_details", "Allow members to view details" }, { "no_new_friends_to_invite", "No new friends available to invite" },
        { "download_percent", "Downloading %1$s%%" }, { "downloading_update_package", "Downloading update package..." },
        { "wallet_binding_load_failed", "Failed to load wallet linking status: %1$s" }, { "reading_profile", "Loading profile..." },
        { "member", "Member" }, { "staking_rules_load_failed", "Failed to load staking rules. Check your network and retry." },
        { "group_permissions_failed", "Failed to load group permissions: %1$s" }, { "group_created", "Group created successfully" },
        { "view_my_qr", "View my QR code" }, { "search_group_members_hint", "Search group members by name, SPX ID, or user ID" },
        { "scan_spx_qr_prompt", "Scan an SPX friend or group QR code" }, { "checkout_address_load_failed", "Failed to load shipping address. Tap to retry." },
        { "exchange_quote_loading", "Loading exchange quote..." }, { "user_not_found", "User not found" }, { "please_select", "Please select" },
        { "completed", "Completed" }, { "group_code_retry", "Failed to get group code. Try again later." }, { "bind_wallet_first", "Link a BSC wallet first" },
        { "eco_pass", "Ecosystem Pass" }, { "wallet_transfer_sheet_hint", "Transfers are processed through the BSC network." },
        { "group_invitation", "[Group invitation]" }, { "wallet_balance_load_failed", "Failed to load wallet balance" }, { "group_management_desc", "Manage members, permissions, and group settings" },
        { "retry_loading", "Retry loading" }, { "no_matching_members", "No matching members" }, { "withdraw_fields_required", "Enter the required withdrawal information" },
        { "reading_release_history", "Loading release history..." }, { "email_or_spx_id_required", "Enter an email address or SPX ID" },
        { "add_friend_desc", "Add friends using email or SPX ID" }, { "wallet_binding_loading", "Loading wallet linking status..." }, { "invite_members", "Invite members" },
        { "group_info_members", "Group information and members" }, { "remark_cleared", "Remark cleared; the chat list will show the user nickname" },
        { "exchange_success", "Exchange successful. Received %1$s SPX001 points" }, { "member", "Member" }, { "group_created", "Group created successfully" },
        { "user_not_found", "User not found" }, { "completed", "Completed" }, { "no_matching_members", "No matching members" },
        { "view_my_qr", "View my QR code" }, { "email_or_spx_id", "Email or SPX ID" }, { "friends_loading_or_empty", "Loading friends, or add friends first" },
        { "search_member_hint", "Search members by name, SPX ID, or user ID" }, { "search_group_members_hint", "Search group members by name, SPX ID, or user ID" }
    };

    static string UnescapeJava(string value) {
        return value.Replace("\\n", "\\n").Replace("\\\"", "\"").Replace("\\\\", "\\");
    }

    static string Repair(string value) {
        if (!CorruptRegex.IsMatch(value)) return value;
        try { return Utf8Strict.GetString(Gbk.GetBytes(value)); }
        catch (DecoderFallbackException) { return value; }
    }

    static Dictionary<string, string> ReadChineseSources(string file) {
        var result = new Dictionary<string, string>();
        foreach (Match match in MapRegex.Matches(File.ReadAllText(file, new UTF8Encoding(false))))
            result[match.Groups[1].Value] = UnescapeJava(match.Groups[2].Value);
        return result;
    }

    static void Main(string[] args) {
        var root = args.Length == 1 ? args[0] : Directory.GetCurrentDirectory();
        var res = Path.Combine(root, "app", "src", "main", "res");
        var chinese = ReadChineseSources(Path.Combine(root, "app", "src", "main", "java", "com", "spx", "chat", "I18n.java"));
        foreach (var dir in Directory.GetDirectories(res, "values*")) {
            var file = Path.Combine(dir, "strings.xml");
            var xml = new XmlDocument();
            xml.Load(file);
            var english = Path.GetFileName(dir) == "values-en";
            foreach (XmlElement node in xml.SelectNodes("/resources/string")) {
                var key = node.GetAttribute("name");
                var value = node.InnerText;
                if (english && English.ContainsKey(key) && (CorruptRegex.IsMatch(value) || value.IndexOf('\ufffd') >= 0)) value = English[key];
                else if (Path.GetFileName(dir) == "values" && chinese.ContainsKey(key)) value = chinese[key];
                else value = Repair(value);
                node.InnerText = value;
            }
            var settings = new XmlWriterSettings { Encoding = new UTF8Encoding(false), Indent = true, NewLineChars = "\n", NewLineHandling = NewLineHandling.Replace };
            using (var writer = XmlWriter.Create(file, settings)) xml.Save(writer);
        }
    }
}
