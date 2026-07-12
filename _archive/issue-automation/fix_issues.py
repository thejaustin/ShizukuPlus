import re

# 1. Fix settings_developer_options.xml
with open("manager/src/main/res/xml/settings_developer_options.xml", "rb") as f:
    dev_opts = f.read().decode("utf-8")

dev_opts = dev_opts.replace("@drawable/ic_restore_24", "@drawable/ic_history_24")

# Extract category_dangerous_experimental block
block_pattern = r"(\s*<af\.shizuku\.manager\.settings\.CollapsiblePreferenceCategory\s+android:key=\"category_dangerous_experimental\".*?</af\.shizuku\.manager\.settings\.CollapsiblePreferenceCategory>)"
match = re.search(block_pattern, dev_opts, re.DOTALL)
if match:
    block = match.group(1)
    # Remove block from dev_opts
    dev_opts = dev_opts.replace(block, "")
    
    # Replace ic_system_update with ic_update_24 in the block
    block = block.replace("@drawable/ic_system_update", "@drawable/ic_update_24")

    # 2. Add to settings_main.xml
    with open("manager/src/main/res/xml/settings_main.xml", "rb") as f:
        main_opts = f.read().decode("utf-8")
    
    # Insert before category_about
    insertion_target = r"(\s*<af\.shizuku\.manager\.settings\.CollapsiblePreferenceCategory\s+android:key=\"category_about\")"
    main_opts = re.sub(insertion_target, block + r"\n\1", main_opts, count=1)

    with open("manager/src/main/res/xml/settings_main.xml", "wb") as f:
        f.write(main_opts.encode("utf-8"))

with open("manager/src/main/res/xml/settings_developer_options.xml", "wb") as f:
    f.write(dev_opts.encode("utf-8"))

print("Fixed!")
